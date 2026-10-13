
import asyncio
from bleak import BleakScanner

async def main():
    print("Пошук BLE-пристроїв протягом 15 секунд...")

    try:
        devices = await BleakScanner.discover(timeout=15)

        if not devices:
            print("BLE-пристроїв не знайдено.")
            return

        for device in devices:
            print(f"Назва: {device.name} | Адреса: {device.address}")

    except Exception as e:
        print(f"ПОМИЛКА: {type(e).__name__}: {e}")

asyncio.run(main())
