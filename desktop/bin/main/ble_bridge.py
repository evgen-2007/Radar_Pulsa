
import asyncio
import json
import sys
from bleak import BleakClient, BleakScanner

# ============================================================
# PulsBreath BLE Bridge
# XIAO ESP32-C6 + MR60BHA2
# Python 3 + bleak
# ============================================================

NAME = "XIAO-C6"

SERVICE_UUID = "7a1e0001-8b4f-4d2a-9c21-1234567890ab"
SENSOR_UUID = "7a1e0002-8b4f-4d2a-9c21-1234567890ab"
CONTROL_UUID = "7a1e0003-8b4f-4d2a-9c21-1234567890ab"

SCAN_TIMEOUT = 20
RECONNECT_DELAY = 3


def status(message):
    print(f"BLE: {message}", flush=True)


def output_json(data):
    """Only JSON telemetry is sent to Java with the JSON: prefix."""
    try:
        if isinstance(data, bytes):
            data = data.decode("utf-8", errors="replace")

        obj = json.loads(data)

        print(
            "JSON:" + json.dumps(
                obj,
                ensure_ascii=False,
                separators=(",", ":")
            ),
            flush=True
        )
    except Exception as exc:
        status(f"Некоректна телеметрія: {exc}")


async def read_commands(queue):
    """Read START/STOP commands sent by Java through stdin."""
    while True:
        line = await asyncio.to_thread(sys.stdin.readline)

        if not line:
            status("Ввід команд закрито")
            return

        command = line.strip().upper()

        if command in ("START", "STOP"):
            await queue.put(command)
            status(f"Отримано команду від Java: {command}")
        elif command:
            status(f"Невідома команда: {command}")


async def find_device():
    status(f"Пошук пристрою {NAME}...")

    device = await BleakScanner.find_device_by_filter(
        lambda d, ad: (d.name or "") == NAME,
        timeout=SCAN_TIMEOUT
    )

    if device is None:
        status(f"{NAME} не знайдено")
        return None

    status(f"Знайдено {device.name}, адреса: {device.address}")
    return device


async def send_commands(client, queue, control):
    """Write commands and read back the device's recording state."""
    while client.is_connected:
        command = await queue.get()

        try:
            if not client.is_connected:
                # The queue preserves the command for the next connection.
                await queue.put(command)
                return

            await client.write_gatt_char(
                control,
                command.encode("ascii"),
                response=True
            )

            status(f"Успішно надіслано на контролер характеристику: {command}")

            # The firmware updates the characteristic value in onWrite().
            # Read it back to verify the state reported by the device.
            await asyncio.sleep(0.25)

            try:
                result = await client.read_gatt_char(control)
                device_state = bytes(result).decode(
                    "utf-8", errors="replace"
                ).strip()

                status(f"Стан запису на пристрої: {device_state}")

                if command == "START" and device_state == "RECORDING":
                    status("Підтверджено: запис активний")
                elif command == "STOP" and device_state == "STOPPED":
                    status("Підтверджено: запис зупинено")
                elif device_state == "ERROR":
                    status(
                        "ПОМИЛКА: пристрій не зміг змінити "
                        "стан запису. Перевір LittleFS."
                    )
                else:
                    status(
                        "УВАГА: стан не відповідає команді; "
                        "перевір відповідь пристрою."
                    )

            except Exception as exc:
                status(
                    "Не вдалося прочитати підтвердження: "
                    f"{type(exc).__name__}: {exc}"
                )

        except Exception as exc:
            status(
                f"Помилка команди {command}: "
                f"{type(exc).__name__}: {exc}"
            )

            # Do not automatically replay a command here:
            # its write may have reached the device before the error.
            # Telemetry reports the actual recording state after reconnect.

        finally:
            queue.task_done()


async def run_connection(device, queue):
    async with BleakClient(device, timeout=30) as client:
        if not client.is_connected:
            raise RuntimeError("BLE-підключення не встановлено")

        status("Підключено до пристрою")

        services = client.services

        service = services.get_service(SERVICE_UUID)
        if service is None:
            raise RuntimeError(
                "BLE-сервіс не знайдено. Перевір прошивку та UUID."
            )

        sensor = services.get_characteristic(SENSOR_UUID)
        control = services.get_characteristic(CONTROL_UUID)

        if sensor is None:
            raise RuntimeError("Характеристику телеметрії не знайдено")

        if control is None:
            raise RuntimeError("Характеристику керування не знайдено")

        status("Сервіс та обидві характеристики знайдено")

        status(f"Телеметрія: {sensor.properties}")
        status(f"Керування: {control.properties}")

        if "notify" not in sensor.properties:
            raise RuntimeError(
                "Телеметрична характеристика не підтримує NOTIFY"
            )

        if "write" not in control.properties:
            raise RuntimeError(
                "Характеристика керування не підтримує WRITE"
            )

        # Read initial recording state.
        try:
            initial = await client.read_gatt_char(control)
            initial_state = bytes(initial).decode(
                "utf-8", errors="replace"
            ).strip()
            status(f"Початковий стан запису: {initial_state}")
        except Exception as exc:
            status(f"Не вдалося прочитати початковий стан: {exc}")

        def notification_callback(_, data):
            output_json(bytes(data))

        # If notification setup fails, reconnect instead of pretending
        # that the telemetry connection is healthy.
        await client.start_notify(sensor, notification_callback)
        status("Підписка на телеметрію активна")

        command_task = asyncio.create_task(
            send_commands(client, queue, control)
        )

        try:
            while client.is_connected:
                await asyncio.sleep(1)
        finally:
            command_task.cancel()
            await asyncio.gather(
                command_task,
                return_exceptions=True
            )

            try:
                await client.stop_notify(sensor)
            except Exception:
                pass


async def main():
    command_queue = asyncio.Queue()

    stdin_task = asyncio.create_task(
        read_commands(command_queue)
    )

    try:
        while True:
            try:
                device = await find_device()

                if device is None:
                    await asyncio.sleep(RECONNECT_DELAY)
                    continue

                await run_connection(device, command_queue)

                status("BLE-з'єднання завершено")

            except asyncio.CancelledError:
                raise

            except Exception as exc:
                status(
                    f"Помилка з'єднання: "
                    f"{type(exc).__name__}: {exc}"
                )

            status(f"Повторна спроба через {RECONNECT_DELAY} с...")
            await asyncio.sleep(RECONNECT_DELAY)

    finally:
        stdin_task.cancel()
        await asyncio.gather(
            stdin_task,
            return_exceptions=True
        )


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        status("Міст зупинено")
    except Exception as exc:
        status(f"Критична помилка: {type(exc).__name__}: {exc}")
