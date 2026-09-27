// FCLGlucoLink — CareSens Air wire-format struct.
//
// 01/08/2026 (editor) — SensorInfo hieronder is LETTERLIJK gekopieerd (via
// een shell-extractie, niet met de hand overgetypt) uit Juggluco's
// `Common/src/main/cpp/air/java.cpp` (GPL-3) — dat bestand definieert 'm
// zelf lokaal (niet in air.hpp). Dit is de eerste helft van de
// device-info-overdracht die de sensor bij het koppelen stuurt (0xC2/0x01
// -bericht) — de rest van de kalibratieprofiel-bytes landt direct in
// air1_opcal4_device_info_t (air.hpp), zie caresensair_bridge.cpp voor de
// twee-staps memcpy die dat doet (mirror van Juggluco's
// airSaveSensorInfo/airSaveSensorInfo2).
#pragma once
#include <cstdint>

struct SensorInfo {
  uint8_t reg[2]; 
  uint8_t sensorVersion;
  float ycept;
  float slope100;
  float slope;
  float r2;
  float t90;
  float slope_ratio;
  char lot[10];
  char sensor_id[12];
  char expiration[6];
  uint16_t stabilizationInterval;
  uint16_t cgmDataInterval;
  uint16_t bleAdvInterval;
  uint8_t bleAdvDuration;
  uint8_t age;
  uint16_t allowedList;
  float maxGlucose;
  float minGlucose;
  uint8_t mCLibraryVersion;
  } __attribute__ ((packed));

