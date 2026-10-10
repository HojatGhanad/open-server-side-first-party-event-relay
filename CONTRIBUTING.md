# 🤝 Contributing

Thanks for taking the time to contribute!

## Ways to help

- 🐛 **Report a bug** with steps to reproduce, your deployment mode, and the relevant part of `/_t/<token>/health.json` (remove tokens and IPs).
- 💡 **Suggest a feature** by opening an issue that describes the problem first.
- 📝 **Improve the docs.** Typos, unclear steps and missing examples are all welcome fixes.
- 🔧 **Send a pull request.**

## Development

Requirements: Java 21, Maven 3.9, Docker (optional).

```bash
cd relay
mvn test                 # unit and web tests, no network or database needed
mvn package              # builds target/fp-relay.jar
```

Run against the mock sGTM:

```bash
python3 tools/mock_sgtm.py &
FP_SGTM_URL=http://127.0.0.1:9999 FP_SGTM_PREVIEW_URL=http://127.0.0.1:9999 FP_TRACE_TOKEN=t \
  java -jar relay/target/fp-relay.jar
```

Build the image:

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml build
```

## Pull requests

- Keep changes focused. One topic per PR.
- Add or update tests for behaviour changes.
- Update the docs when you change configuration or behaviour.
- Make sure `mvn test` passes.

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
