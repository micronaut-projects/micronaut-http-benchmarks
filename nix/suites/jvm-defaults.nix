{ lib, ... }:
{
  benchmark.suite.runModules = [{
    benchmark.jvm.args = lib.mkDefault [
      "-XX:+UseZGC"
      "-Xms12G"
      "-Xmx12G"
      "-Dio.netty.iouring.iosqeAsyncThreshold=2147483647"
      "-Dio.netty.iouring.ringSize=8192"
    ];
  }];
}
