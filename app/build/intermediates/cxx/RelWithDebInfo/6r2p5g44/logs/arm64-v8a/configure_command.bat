@echo off
"C:\\Users\\palap\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\cmake.exe" ^
  "-HD:\\_AAPS-AS\\___FCL_GlucoLink\\FCLGlucoLink-v9.6-WV\\app\\src\\main\\cpp" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=26" ^
  "-DANDROID_PLATFORM=android-26" ^
  "-DANDROID_ABI=arm64-v8a" ^
  "-DCMAKE_ANDROID_ARCH_ABI=arm64-v8a" ^
  "-DANDROID_NDK=C:\\Users\\palap\\AppData\\Local\\Android\\Sdk\\ndk\\26.1.10909125" ^
  "-DCMAKE_ANDROID_NDK=C:\\Users\\palap\\AppData\\Local\\Android\\Sdk\\ndk\\26.1.10909125" ^
  "-DCMAKE_TOOLCHAIN_FILE=C:\\Users\\palap\\AppData\\Local\\Android\\Sdk\\ndk\\26.1.10909125\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=C:\\Users\\palap\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\ninja.exe" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=D:\\_AAPS-AS\\___FCL_GlucoLink\\FCLGlucoLink-v9.6-WV\\app\\build\\intermediates\\cxx\\RelWithDebInfo\\6r2p5g44\\obj\\arm64-v8a" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=D:\\_AAPS-AS\\___FCL_GlucoLink\\FCLGlucoLink-v9.6-WV\\app\\build\\intermediates\\cxx\\RelWithDebInfo\\6r2p5g44\\obj\\arm64-v8a" ^
  "-DCMAKE_BUILD_TYPE=RelWithDebInfo" ^
  "-BD:\\_AAPS-AS\\___FCL_GlucoLink\\FCLGlucoLink-v9.6-WV\\app\\.cxx\\RelWithDebInfo\\6r2p5g44\\arm64-v8a" ^
  -GNinja
