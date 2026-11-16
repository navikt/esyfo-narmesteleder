#!/bin/sh
set -eu

work=$(mktemp -d "${TMPDIR:-/tmp}/dialog-alerts.XXXXXX")
trap 'rm -f "$work/alerts.rules.yaml" "$work/alerts.test.yaml"; rmdir "$work"' EXIT
ruby --disable-gems -ryaml -e 'puts YAML.dump(YAML.load_file(ARGV[0]).fetch("spec"))' nais/alerts.yaml > "$work/alerts.rules.yaml"
cp nais/alerts.test.yaml "$work/alerts.test.yaml"
docker run --rm --user "$(id -u):$(id -g)" --entrypoint /bin/promtool -v "$work:/rules:ro" -w /rules prom/prometheus:v3.13.1 check rules alerts.rules.yaml
docker run --rm --user "$(id -u):$(id -g)" --entrypoint /bin/promtool -v "$work:/rules:ro" -w /rules prom/prometheus:v3.13.1 test rules alerts.test.yaml
