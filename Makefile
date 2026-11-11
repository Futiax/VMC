# Makefile — Compile le binaire C (mcmm) + le resource pack + le plugin Paper (.jar)

CDIR        ?= ../MinecraftVideo/c version
NATIVES_DIR := src/main/resources/natives
MCMM_BIN    := $(NATIVES_DIR)/mcmm-linux-x64

.PHONY: all natives c jar keep pack clean help

# Cible par defaut : recompile le C, incremente la version (pour que le serveur
# re-extraie le nouveau binaire mcmm au demarrage) et construit le .jar
all: natives
	./build.sh

# Alias pratique : recompile uniquement le binaire C
c: natives

natives:
	@if [ -f "$(CDIR)/mcmm.c" ]; then \
		./prepare-natives.sh && \
		cp "$(CDIR)/mcmm.c" "$(CDIR)/vmc_data.h" "$(CDIR)/vmc_daemon.h" "$(NATIVES_DIR)/" 2>/dev/null || true; \
	else \
		echo "Compilation directe depuis $(NATIVES_DIR)/mcmm.c..."; \
		gcc -O3 -fno-trapping-math -march=x86-64-v2 -DNDEBUG -fopenmp -static \
			"$(NATIVES_DIR)/mcmm.c" -o "$(MCMM_BIN)" -lz -lm; \
		echo "  -> $(MCMM_BIN)"; \
	fi

# Recompile le C et le .jar SANS incrementer la version dans pom.xml
keep: natives
	./build.sh keep

# Construit uniquement le .jar (avec bump de version) sans recompiler le C
jar:
	./build.sh

# Regenere uniquement le resource pack (pack.zip)
pack:
	./make-pack.sh

clean:
	mvn -q clean
	rm -rf "$(CDIR)/build-static"

help:
	@echo "Cibles disponibles :"
	@echo "  make          Recompile le C + pack + .jar (incremente la version)"
	@echo "  make keep     Recompile le C + pack + .jar (garde la meme version)"
	@echo "  make c        Recompile uniquement le binaire C (mcmm-linux-x64)"
	@echo "  make jar      Recompile uniquement le .jar Java"
	@echo "  make pack     Regenere uniquement pack.zip"
	@echo "  make clean    Nettoie target/ et build-static/"
