/*
 * fp-transport: browser side of protected transport.
 *
 * Served by the relay in front of the tag loader. Requests that Google's tag sends to the
 * tenant's collect origin are wrapped in an opaque envelope and posted to the tenant's
 * protected segment. The relay unwraps them and forwards the original request to sGTM.
 *
 * Only requests to this tenant's origin and collect prefix are touched. Every patch falls
 * back to the original browser API on any error.
 *
 * Placeholders (__ORIGIN__, __PREFIX__, ...) are filled in per tenant by ShimInjector.
 */
(function (w, d) {
  'use strict';

  var ORIGIN = '__ORIGIN__';
  var PREFIX = '__PREFIX__';
  var SEG    = '__SEGMENT__';
  var KEY    = '__KEY__';
  var LOADER = '__LOADER__';
  var ALIAS  = '__ALIAS__';

  var BASE   = ORIGIN + PREFIX;
  var TARGET = BASE + '/' + SEG;

  if (!w.TextEncoder || !w.btoa) return;

  var enc = new TextEncoder();
  var keyBytes = enc.encode(KEY);

  // True for any URL under this tenant's collect base, except the loader and the segment itself.
  function isOurs(u) {
    if (typeof u !== 'string' || u.lastIndexOf(BASE, 0) !== 0) return false;
    var rest = u.substring(BASE.length);
    if (rest.lastIndexOf('/' + SEG, 0) === 0) return false;
    if (LOADER && rest.lastIndexOf(LOADER, 0) === 0) return false;
    if (ALIAS && rest.lastIndexOf(ALIAS, 0) === 0) return false;
    return rest.charAt(0) === '/';
  }

  // Path and query relative to the collect base.
  function rel(u) {
    return String(u).substring(BASE.length);
  }

  function wrap(method, url, body) {
    var bytes = enc.encode(JSON.stringify({
      m: method,
      u: rel(url),
      b: typeof body === 'string' ? body : ''
    }));
    var bin = '';
    for (var i = 0; i < bytes.length; i++) {
      bin += String.fromCharCode(keyBytes.length ? (bytes[i] ^ keyBytes[i % keyBytes.length]) : bytes[i]);
    }
    return w.btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  // navigator.sendBeacon
  var beacon = w.navigator && w.navigator.sendBeacon;
  if (beacon) {
    var origBeacon = beacon.bind(w.navigator);
    w.navigator.sendBeacon = function (url, data) {
      if (!isOurs(url)) return origBeacon(url, data);
      try {
        // Non-string bodies cannot be read synchronously, so they are sent as-is.
        if (data != null && typeof data !== 'string') return origBeacon(url, data);
        return origBeacon(TARGET, new Blob([wrap('POST', url, data)],
          { type: 'text/plain;charset=UTF-8' }));
      } catch (e) {
        return origBeacon(url, data);
      }
    };
  }

  // fetch
  var origFetch = w.fetch;
  if (origFetch) {
    w.fetch = function (input, init) {
      try {
        var url = typeof input === 'string' ? input : (input && input.url);
        if (!isOurs(url)) return origFetch.apply(w, arguments);
        var method = (init && init.method) || (input && input.method) || 'GET';
        var body = init && typeof init.body === 'string' ? init.body : '';
        return origFetch.call(w, TARGET, {
          method: 'POST',
          body: wrap(method, url, body),
          headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
          credentials: 'include',
          keepalive: true,
          mode: 'cors'
        });
      } catch (e) {
        return origFetch.apply(w, arguments);
      }
    };
  }

  // XMLHttpRequest: the URL is known at open(), the body at send().
  var XHR = w.XMLHttpRequest;
  if (XHR && XHR.prototype) {
    var origOpen = XHR.prototype.open;
    var origSend = XHR.prototype.send;

    XHR.prototype.open = function (method, url) {
      try {
        if (isOurs(url)) {
          this.__fp = { m: method || 'GET', u: url };
          var args = Array.prototype.slice.call(arguments);
          args[0] = 'POST';
          args[1] = TARGET;
          return origOpen.apply(this, args);
        }
        this.__fp = null;
      } catch (e) {
        this.__fp = null;
      }
      return origOpen.apply(this, arguments);
    };

    XHR.prototype.send = function (body) {
      var fp = this.__fp;
      if (fp) {
        try {
          this.withCredentials = true;
          this.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
          return origSend.call(this, wrap(fp.m, fp.u, body));
        } catch (e) {
          // fall through to a plain send
        }
      }
      return origSend.apply(this, arguments);
    };
  }
})(window, document);
