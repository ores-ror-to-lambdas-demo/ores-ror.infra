.PHONY: package admit

package:
	mvn -q -DskipTests package

# Build-time admission only. Runtime never shells out to gs-compiler.
admit:
	cargo run --manifest-path ../gs-compiler/Cargo.toml -- build ../ores-ror.rb/graal \
		--language truffleruby \
		--entrypoint handler \
		--http-origin "$${DATA_API_URL}" \
		--out-dir ./dist
