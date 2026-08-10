# Requires the "QTI Content" category to exist first — run master_category_create
# (or its "QTI Content" block) before this script.
curl -L -X POST '{{host}}/object/category/definition/v4/create' \
-H 'Content-Type: application/json' \
--data-raw '{
  "request": {
    "objectCategoryDefinition": {
      "categoryId": "obj-cat:qti-content",
      "targetObjectType": "Content",
      "objectMetadata": {
        "config": {},
        "schema": {
          "properties": {
            "qtiVersion": {
              "type": "string"
            },
            "previewUrl": {
              "type": "string"
            },
            "itemList": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "identifier": { "type": "string" },
                  "href": { "type": "string" },
                  "stimulusRefs": {
                    "type": "array",
                    "items": { "type": "string" }
                  }
                }
              }
            },
            "testList": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "identifier": { "type": "string" },
                  "href": { "type": "string" },
                  "itemRefs": {
                    "type": "array",
                    "items": { "type": "string" }
                  }
                }
              }
            },
            "stimulusList": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "identifier": { "type": "string" },
                  "href": { "type": "string" }
                }
              }
            }
          }
        }
      }
    }
  }
}'
