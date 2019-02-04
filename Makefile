deploy: build
	cp target/cloud-0.1.0-SNAPSHOT-standalone.jar ../working/uForgeOraclePublishConnector/src/main/resources/clojureConnector/clojureConnector/1.0.0/clojureConnector-1.0.0.jar

build:
	lein uberjar
