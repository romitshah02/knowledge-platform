#!/usr/bin/env bash
curl -L -X POST '{{host}}/object/category/definition/v4/create' \
-H 'Content-Type: application/json' \
--data-raw '{
  "request": {
    "objectCategoryDefinition": {
      "categoryId": "obj-cat:upload-question",
      "targetObjectType": "Question",
      "objectMetadata": {
        "config": {},
        "schema": {
          "properties": {
            "interactionTypes": {
              "type": "array",
              "items": { "type": "string", "enum": ["file-upload"] }
            },
            "mimeType": {
              "type": "string",
              "enum": ["application/vnd.sunbird.question"]
            },
            "primaryCategory": {
              "type": "string",
              "enum": ["Upload Question"]
            },
            "qType": {
              "type": "string",
              "enum": ["UPLOAD"]
            }
          }
        }
      }
    }
  }
}'