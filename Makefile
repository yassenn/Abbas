# Reusable entry points (also what the remote assistant runs through Claude Connect).
.PHONY: build audit verify-models verify-candidates device-audit catalog-key catalog catalog-verify

build:
	./scripts/build-apk.sh

audit:
	python3 tools/security/android_static_audit.py . --module app --out dist/security-audit.md --fail-on none

verify-models:
	python3 tools/models/verify_models.py app/src/main/java/ai/alal/app/data/ModelConfig.kt --out dist/models-verified.json

verify-candidates:
	python3 tools/models/verify_models.py tools/models/candidates.json --out dist/candidates-verified.json

device-audit:
	./tools/security/android_device_audit.sh ai.alal.app dist/device-audit.md

catalog-key:
	./tools/catalog/catalog.sh keygen

catalog:
	./tools/catalog/catalog.sh publish

catalog-verify:
	./tools/catalog/catalog.sh verify
