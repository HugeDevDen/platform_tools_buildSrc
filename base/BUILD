load("//tools/base/bazel:maven.bzl", "maven_pom")

exports_files(["version.properties"])

# TODO: Extend POM generation to support what's needed for publishing.
maven_pom(
  name = "base_version",
  version = "25.3.0-beta3",
  visibility = ["//visibility:public"],
)

maven_pom(
  name = "build_version",
  version = "2.3.0-beta3",
  visibility = ["//visibility:public"],
)

maven_pom(
  name = "experimental_version",
  version = "0.9.0-beta3",
  visibility = ["//visibility:public"],
)