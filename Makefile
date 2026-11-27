.PHONY: all build pack clean server keep engine

VERSION := $(shell grep -m1 '<version>' pom.xml | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')
JAR := target/mcdata-$(VERSION).jar

all: build

build:
	./build.sh

pack:
	./make-pack.sh

clean:
	rm -rf target

distclean: clean
	rm -f src/main/resources/pack.zip

# Copy the built jar into the test server's plugins dir.
server: build
	cp $(JAR) server/plugins/ 2>/dev/null || mkdir -p server/plugins && cp $(JAR) server/plugins/

# Build + copy in one go (kept name for muscle memory).
keep: server
