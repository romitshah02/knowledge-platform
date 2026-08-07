#!/usr/bin/env bash
curl -L -X POST '{{host}}/object/category/definition/v4/create' \
-H 'Content-Type: application/json' \
--data-raw '{
  "request": {
    "objectCategoryDefinition": {
      "categoryId": "obj-cat:position-object-question",
      "targetObjectType": "Question",
      "objectMetadata": {
        "config": {},
        "schema": {
          "properties": {
            "interactionTypes": {
              "type": "array",
              "items": { "type": "string", "enum": ["canvas"] }
            },
            "mimeType": {
              "type": "string",
              "enum": ["application/vnd.sunbird.question"]
            },
            "primaryCategory": {
              "type": "string",
              "enum": ["Position Object Question"]
            },
            "qType": {
              "type": "string",
              "enum": ["POSITION-OBJECT"]
            }
          }
        }
      }
    }
  }
}'