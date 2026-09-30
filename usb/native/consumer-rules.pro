# Preserve JNI entrypoints and the Native USBFS backend name for release shrinking.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class io.github.ncorror.nekoflash.fastboot.transport.NativeUsbfsBackend {
    native <methods>;
}
