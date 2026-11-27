.PHONY: all build pack clean server keep engine level natives

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

# Moteur Doom (doomgeneric patche + native/doommc_engine.c), embarque dans le jar
engine:
	$(MAKE) -C native
	cp native/build/doommc-engine src/main/resources/doommc-engine

# Niveau Doom (textures de donnees + shader doom_view) depuis le WAD Freedoom
WAD ?= /usr/share/games/doom/freedoom1.wad
level:
	python3 tools/doom/gen_level.py $(WAD) E1M1

# mcmm (VMC) recompile depuis src/main/resources/natives/mcmm.c
natives:
	gcc -O3 -fno-trapping-math -march=x86-64-v2 -DNDEBUG -fopenmp -static \
		src/main/resources/natives/mcmm.c -o src/main/resources/natives/mcmm-linux-x64 -lz -lm
