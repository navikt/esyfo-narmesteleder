#!/usr/bin/env python3

import argparse
import json
import random
import sys
import uuid
from datetime import date, datetime, timedelta, timezone
from typing import Callable, List, Optional, Sequence, Tuple

try:
    from confluent_kafka import Producer
except ImportError as kafka_import_error:
    Producer = None
    KAFKA_IMPORT_ERROR = str(kafka_import_error)
else:
    KAFKA_IMPORT_ERROR = None

KAFKA_FLUSH_TIMEOUT_SECONDS = 30


def error(message: str, parser: Optional[argparse.ArgumentParser] = None) -> None:
    print(f"Error: {message}", file=sys.stderr)
    if parser is not None:
        parser.print_usage(sys.stderr)


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate local Leesah test records for stdout or Kafka.",
    )
    parser.add_argument(
        "number_of_records",
        type=int,
        help="number of records to generate",
    )
    parser.add_argument(
        "--output-mode",
        choices=["stdout", "kafka"],
        default="stdout",
        help="where to send generated records (default: stdout)",
    )
    parser.add_argument(
        "--topic",
        help="Kafka topic to publish to (required for --output-mode kafka)",
    )
    parser.add_argument(
        "--bootstrap-server",
        default="localhost:9092",
        help="Kafka bootstrap server for --output-mode kafka (default: localhost:9092)",
    )
    return parser


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = create_argument_parser()
    arguments = parser.parse_args(argv)

    if arguments.number_of_records < 0:
        error("number-of-records must be a non-negative integer", parser)
        raise SystemExit(1)

    if arguments.output_mode == "kafka" and not arguments.topic:
        error("--topic is required when --output-mode kafka is used", parser)
        raise SystemExit(1)

    if arguments.output_mode != "kafka" and arguments.topic:
        error("--topic can only be used when --output-mode kafka is selected", parser)
        raise SystemExit(1)

    return arguments


def require_kafka_producer() -> Callable[..., object]:
    if Producer is None:
        print(
            "Error: Kafka mode requires the optional Python dependency 'confluent-kafka'.",
            file=sys.stderr,
        )
        print(
            "Install it locally with: python3 -m pip install confluent-kafka",
            file=sys.stderr,
        )
        if KAFKA_IMPORT_ERROR is not None:
            print(f"Import error: {KAFKA_IMPORT_ERROR}", file=sys.stderr)
        raise SystemExit(1)

    return Producer


def random_number_string(length: int) -> str:
    lower_bound = 10 ** (length - 1)
    upper_bound = (10**length) - 1
    return str(random.randint(lower_bound, upper_bound))


def random_past_dates() -> Tuple[date, Optional[date], str]:
    today = date.today()
    aktiv_fom = today - timedelta(days=random.randint(30, 3650))

    if random.choice([True, False]):
        return aktiv_fom, None, "NY_LEDER"

    aktiv_tom = aktiv_fom + timedelta(days=random.randint(0, (today - aktiv_fom).days))
    status = random.choice([
        "DEAKTIVERT_ARBEIDSTAKER",
        "DEAKTIVERT_LEDER",
    ])
    return aktiv_fom, aktiv_tom, status


def build_record() -> Tuple[str, str]:
    narmeste_leder_id = str(uuid.uuid4())
    aktiv_fom, aktiv_tom, status = random_past_dates()
    message = {
        "narmesteLederId": narmeste_leder_id,
        "fnr": random_number_string(11),
        "orgnummer": random_number_string(9),
        "narmesteLederFnr": random_number_string(11),
        "narmesteLederTelefonnummer": random_number_string(8),
        "narmesteLederEpost": f"{narmeste_leder_id[:8]}@example.com",
        "aktivFom": aktiv_fom.isoformat(),
        "aktivTom": aktiv_tom.isoformat() if aktiv_tom else None,
        "arbeidsgiverForskutterer": random.choice([True, False, None]),
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z"),
        "status": status,
    }
    return narmeste_leder_id, json.dumps(message, separators=(",", ":"))


def emit_stdout(record_count: int) -> int:
    for _ in range(record_count):
        key, payload = build_record()
        print(f"{key}={payload}")

    return 0


def kafka_message_key(message) -> str:
    key = message.key()
    if key is None:
        return "<unknown>"
    if isinstance(key, bytes):
        return key.decode("utf-8")
    return str(key)


def publish_to_kafka(record_count: int, topic: str, bootstrap_server: str) -> int:
    producer_class = require_kafka_producer()
    delivery_failures: List[str] = []

    producer = producer_class({"bootstrap.servers": bootstrap_server})

    def delivery_report(error_value, message) -> None:
        if error_value is not None:
            delivery_failures.append(
                f"{kafka_message_key(message)}: {error_value}"
            )

    try:
        for _ in range(record_count):
            key, payload = build_record()
            producer.poll(0)
            producer.produce(topic, key=key, value=payload, callback=delivery_report)
    except BufferError as exc:
        print(f"Error: failed to queue Kafka message: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Error: failed to publish Kafka messages: {exc}", file=sys.stderr)
        return 1

    remaining_messages = producer.flush(timeout=KAFKA_FLUSH_TIMEOUT_SECONDS)

    if remaining_messages != 0:
        print(
            "Error: "
            f"{remaining_messages} Kafka message(s) were not delivered within "
            f"{KAFKA_FLUSH_TIMEOUT_SECONDS} seconds before shutdown.",
            file=sys.stderr,
        )
        return 1

    if delivery_failures:
        for failure in delivery_failures:
            print(f"Error: Kafka delivery failed for record {failure}", file=sys.stderr)
        return 1

    return 0


def main() -> int:
    arguments = parse_arguments(sys.argv[1:])

    if arguments.output_mode == "stdout":
        return emit_stdout(arguments.number_of_records)

    return publish_to_kafka(
        record_count=arguments.number_of_records,
        topic=arguments.topic,
        bootstrap_server=arguments.bootstrap_server,
    )


if __name__ == "__main__":
    raise SystemExit(main())
