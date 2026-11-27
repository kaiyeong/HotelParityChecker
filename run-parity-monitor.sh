#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Hotel Rate Parity Monitor - Background Runner Script
# ==============================================================================

# External directories & files (customize these for your server)
APP_DIR="/opt/hotel-parity"
JAR_FILE="${APP_DIR}/hotel-parity-checker-1.0.0.jar"
CONFIG_FILE="${APP_DIR}/config/application.properties"
LOG_DIR="${APP_DIR}/logs"
PID_FILE="${APP_DIR}/parity-monitor.pid"
STDOUT_LOG="${LOG_DIR}/console.log"

# Optional JVM flags (timezone ensures Quartz cron runs in your local time)
JAVA_OPTS="-Xms256m -Xmx512m -Duser.timezone=Asia/Kuala_Lumpur"

mkdir -p "${LOG_DIR}"

is_running() {
  if [[ -f "${PID_FILE}" ]]; then
    local pid
    pid=$(cat "${PID_FILE}")
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      return 0
    fi
  fi
  return 1
}

start_app() {
  local extra_args="${1:-}"
  if is_running; then
    echo "Hotel Parity Monitor is already running (PID $(cat "${PID_FILE}"))."
    exit 0
  fi

  if [[ ! -f "${JAR_FILE}" ]]; then
    echo "ERROR: JAR not found at ${JAR_FILE}"
    exit 1
  fi
  if [[ ! -f "${CONFIG_FILE}" ]]; then
    echo "ERROR: External config not found at ${CONFIG_FILE}"
    exit 1
  fi

  cd "${APP_DIR}"
  nohup java ${JAVA_OPTS} -Dconfig.file="${CONFIG_FILE}" -jar "${JAR_FILE}" ${extra_args} >> "${STDOUT_LOG}" 2>&1 &
  local pid=$!
  echo "${pid}" > "${PID_FILE}"
  echo "Started Hotel Parity Monitor in background (PID ${pid})."
  echo "Console log: ${STDOUT_LOG}"
  echo "App log:     ${LOG_DIR}/hotel-parity.log"
}

stop_app() {
  if ! is_running; then
    echo "Hotel Parity Monitor is not running."
    rm -f "${PID_FILE}"
    return 0
  fi
  local pid
  pid=$(cat "${PID_FILE}")
  echo "Stopping Hotel Parity Monitor (PID ${pid})..."
  kill "${pid}"
  for _ in {1..15}; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      rm -f "${PID_FILE}"
      echo "Stopped."
      return 0
    fi
    sleep 1
  done
  echo "Force killing PID ${pid}..."
  kill -9 "${pid}" || true
  rm -f "${PID_FILE}"
}

status_app() {
  if is_running; then
    echo "Hotel Parity Monitor is RUNNING (PID $(cat "${PID_FILE}"))."
  else
    echo "Hotel Parity Monitor is STOPPED."
  fi
}

case "${1:-start}" in
  start)
    start_app ""
    ;;
  admin)
    start_app "--admin-only"
    ;;
  run-now)
    cd "${APP_DIR}"
    nohup java ${JAVA_OPTS} -Dconfig.file="${CONFIG_FILE}" -jar "${JAR_FILE}" --run-now >> "${STDOUT_LOG}" 2>&1 &
    echo "Triggered --run-now in background (PID $!). Logs: ${LOG_DIR}/hotel-parity.log"
    ;;
  stop)
    stop_app
    ;;
  restart)
    stop_app
    start_app ""
    ;;
  status)
    status_app
    ;;
  *)
    echo "Usage: $0 {start|stop|restart|status|run-now|admin}"
    exit 1
    ;;
esac
