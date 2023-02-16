
FROM java_includes AS java_includes

FROM dockcross/windows-static-x64
COPY --from=java_includes /java_windows /java_windows

CMD $CXX -I /java_windows/include -I /java_windows/include/win32 $BUILD_ARGS
