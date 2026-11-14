#!/bin/sh
set -eu

url='http://elasticsearch:9200/rag_knowledge_v1'
status=$(curl -s -o /dev/null -w '%{http_code}' -I "$url")
if [ "$status" = '404' ]; then
  curl -fsS -X PUT -H 'Content-Type: application/json' --data-binary @/rag-index.json "$url"
elif [ "$status" != '200' ]; then
  echo "Elasticsearch index check failed: HTTP $status" >&2
  exit 1
fi
