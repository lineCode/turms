#!/bin/bash

BASEDIR="$(dirname $0)"
PROTO_DIR="$BASEDIR/src/main/proto"
JAVA_OUTPUT_DIR="$BASEDIR/src/main/java"
echo $BASEDIR
echo $PROTO_DIR
FILES=$(find $PROTO_DIR -type f -name "*.proto")
for proto in $FILES; do
    echo $proto
    sed -zi 's/im.turms.server.common.access.client.dto./im.turms.client.model.proto./' $proto
    # We don't use "kotlin_out" because protoc has a poor support for Kotlin currently
    protoc -I=$PROTO_DIR --java_out=lite:$JAVA_OUTPUT_DIR $proto
done

find $JAVA_OUTPUT_DIR -type f -name "*OuterClass.java" | xargs rm -f

echo "Done"
