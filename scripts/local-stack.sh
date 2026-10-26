#!/usr/bin/env bash
# Stack completo SEM Docker (PostgreSQL, Kafka, Redis, Debezium embarcados + N replicas dos servicos).
#   ./scripts/local-stack.sh start [replicas]   # sobe em background e espera ficar pronto
#   ./scripts/local-stack.sh stop
# Enderecos ficam em local-stack/target/stack.properties. Requer JDK 21 (JAVA_HOME).
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="local-stack/target/stack.properties"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; *) SEP=":";; esac

case "${1:-start}" in
  start)
    REPLICAS="${2:-1}"
    mvn -B -ntp -q -pl local-stack -am install -DskipTests
    mvn -B -ntp -q -pl local-stack dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    rm -f "$OUT" "$OUT.stop"
    nohup java -Xmx3g -cp "local-stack/target/classes${SEP}$(cat local-stack/target/cp.txt)" \
      com.saga.localstack.LocalStack --replicas="$REPLICAS" --out="$OUT" "${@:3}" \
      > local-stack/target/local-stack.log 2>&1 &
    echo $! > local-stack/target/local-stack.pid
    echo "aguardando o stack ($REPLICAS replica(s) de cada servico)..."
    for _ in $(seq 1 180); do
      [ -f "$OUT" ] && { echo "pronto: $OUT"; exit 0; }
      sleep 2
    done
    echo "timeout; veja local-stack/target/local-stack.log" >&2; exit 1 ;;
  stop)
    touch "$OUT.stop"
    sleep 5
    [ -f local-stack/target/local-stack.pid ] && kill "$(cat local-stack/target/local-stack.pid)" 2>/dev/null || true
    echo "parado" ;;
  *) echo "uso: $0 start [replicas] | stop"; exit 1 ;;
esac
