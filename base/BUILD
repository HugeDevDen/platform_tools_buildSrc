load("//tools/base/bazel:maven.bzl", "maven_pom")

exports_files(["version.properties"])

# TODO: Extend POM generation to support what's needed for publishing.
maven_pom(
  name = "base_version",
  version = "25.3.0-dev",
  visibility = ["//visibility:public"],
)
