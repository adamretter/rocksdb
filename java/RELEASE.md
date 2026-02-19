## Cross-building

RocksDB can be built as a single self-contained cross-platform JAR. The cross-platform jar can be used on any 64-bit macOS/Windows/Linux system, or 32-bit Linux system.

Building a cross-platform JAR requires:

 * [Docker](https://www.docker.com/docker-community)
 * A macOS machine that can compile RocksDB.
 * Java 8 set as JAVA_HOME.
 * Apache Maven 3.9.0+

Once you have these items, run this make command from RocksDB's root source directory:

    make jclean clean rocksdbjavastaticreleasedocker

This command will build RocksDB natively on macOS, and will then spin up Docker containers to build RocksDB for 32-bit and 64-bit Linux with glibc, and 32-bit and 64-bit Linux with musl libc.

You can find all native binaries and JARs in the java/target-native directory upon completion:

    librocksdbjni-linux32.so
    librocksdbjni-linux64.so
    librocksdbjni-linux64-musl.so
    librocksdbjni-linux32-musl.so
    librocksdbjni-osx.jnilib
    rocksdbjni-x.y.z-javadoc.jar
    rocksdbjni-x.y.z-linux32.jar
    rocksdbjni-x.y.z-linux64.jar
    rocksdbjni-x.y.z-linux64-musl.jar
    rocksdbjni-x.y.z-linux32-musl.jar
    rocksdbjni-x.y.z-osx.jar
    rocksdbjni-x.y.z-sources.jar
    rocksdbjni-x.y.z.jar

Where x.y.z is the built version number of RocksDB.

## Maven publication

Your `~/.m2/settings.xml` should contain within its `<servers>` element:

```xml
    <server>
      <id>central</id>
      <username>your-maven-central-username</username>
      <password>your-maven-central-token</password>
    </server>
```

From RocksDB's root directory:

    make rocksdbjavastaticpublish

This command will [stage the JAR artifacts on Maven Central repository](https://central.sonatype.com/).

To release the staged artifacts:

1. Go to [https://central.sonatype.com/publishing](https://central.sonatype.com/publishing) and look for a new entry for `rocksdbjni-x.y.x` on the left-hand-side of the page under Deployments.
2. Select the rocksdbjni-x.y.z deployment repository, and inspect its contents.
3. Click the `Publish` button on the right-hand-side of the page.

After the release has occurred, the artifacts will be synced to Maven central within 24-48 hours.
