# Topic teamsykmelding.syfo-narmesteleder-leesah

This folder contains local test data for the Kafka topic `teamsykmelding.syfo-narmesteleder-leesah`.

## Generate test records

Use `generate-leesah-messages.py` to generate keyed test records that match `NarmestelederLeesahKafkaMessage`.

The script supports two output modes:

- `stdout` (default): writes `key=value` lines to stdout
- `kafka`: publishes records directly to Kafka

The existing stdout flow still works:

```bash
./generate-leesah-messages.py 3
./generate-leesah-messages.py 10 > test-records.txt
```

You can also choose the output mode explicitly:

```bash
./generate-leesah-messages.py 3 --output-mode stdout
```

## Publish directly to Kafka

Kafka mode requires a topic and uses `localhost:9092` as the default bootstrap server.

```bash
./generate-leesah-messages.py 3 --output-mode kafka --topic teamsykmelding.syfo-narmesteleder-leesah
./generate-leesah-messages.py 3 --output-mode kafka --topic teamsykmelding.syfo-narmesteleder-leesah --bootstrap-server localhost:9092
```

Messages are published with:

- Kafka key: `narmesteLederId`
- Kafka value: single-line JSON payload

The script waits for delivery confirmation before it exits, and returns a non-zero exit code if publishing fails.

## Optional Python dependency for Kafka mode

Stdout mode works without extra dependencies.

Kafka mode requires `confluent-kafka`:

```bash
python3 -m pip install confluent-kafka
```

If the dependency is missing, Kafka mode fails with a clear error message while stdout mode continues to work.

## Output format

In `stdout` mode, each output line is on the form:

```text
<uuid>={"narmesteLederId":"<same uuid>",...}
```

- `<uuid>` is the Kafka key.
- The value is a single-line JSON payload.
- `narmesteLederId` in the JSON matches the key on the same line.
