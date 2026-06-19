#!/usr/bin/env bash
# Seed NCF framework data on the local taxonomy service (port 9000).
# Run this once after starting the taxonomy service.
# Usage: bash scripts/seed-ncf-framework.sh

BASE="http://localhost:9000"
H='-H "Content-Type: application/json"'

echo_step() { echo; echo "==> $1"; }

check() {
  if echo "$1" | grep -q '"status":"successful"'; then
    echo "    OK"
  else
    echo "    RESPONSE: $1"
  fi
}

# ─────────────────────────────────────────────
# STEP 1 – Ensure master categories exist
# ─────────────────────────────────────────────
echo_step "Step 1: Create master categories"

for cat in board medium gradeLevel subject; do
  echo -n "  Creating master category '$cat'... "
  R=$(curl -s -X POST "$BASE/framework/v3/category/master/create" \
    -H "Content-Type: application/json" \
    -d "{\"request\":{\"category\":{\"name\":\"$cat\",\"code\":\"$cat\"}}}")
  check "$R"
done

# ─────────────────────────────────────────────
# STEP 2 – Update master categories with field mappings
#          (matches the existing framework-master-category script)
# ─────────────────────────────────────────────
echo_step "Step 2: Update master category field mappings"

echo -n "  board... "
R=$(curl -s -X PATCH "$BASE/framework/v3/category/master/update/board" \
  -H "Content-Type: application/json" \
  -d '{"request":{"category":{"targetIdFieldName":"targetBoardIds","searchLabelFieldName":"se_boards","searchIdFieldName":"se_boardIds","orgIdFieldName":"boardIds"}}}')
check "$R"

echo -n "  medium... "
R=$(curl -s -X PATCH "$BASE/framework/v3/category/master/update/medium" \
  -H "Content-Type: application/json" \
  -d '{"request":{"category":{"targetIdFieldName":"targetMediumIds","searchLabelFieldName":"se_mediums","searchIdFieldName":"se_mediumIds","orgIdFieldName":"mediumIds"}}}')
check "$R"

echo -n "  gradeLevel... "
R=$(curl -s -X PATCH "$BASE/framework/v3/category/master/update/gradeLevel" \
  -H "Content-Type: application/json" \
  -d '{"request":{"category":{"targetIdFieldName":"targetGradeLevelIds","searchLabelFieldName":"se_gradeLevels","searchIdFieldName":"se_gradeLevelIds","orgIdFieldName":"gradeLevelIds"}}}')
check "$R"

echo -n "  subject... "
R=$(curl -s -X PATCH "$BASE/framework/v3/category/master/update/subject" \
  -H "Content-Type: application/json" \
  -d '{"request":{"category":{"targetIdFieldName":"targetSubjectIds","searchLabelFieldName":"se_subjects","searchIdFieldName":"se_subjectIds","orgIdFieldName":"subjectIds"}}}')
check "$R"

# ─────────────────────────────────────────────
# STEP 3 – Create the NCF framework
# ─────────────────────────────────────────────
echo_step "Step 3: Create NCF framework"
R=$(curl -s -X POST "$BASE/framework/v3/create" \
  -H "Content-Type: application/json" \
  -d '{"request":{"framework":{"name":"NCF","code":"NCF","description":"National Curriculum Framework","type":"K-12"}}}')
echo "  $R"

# ─────────────────────────────────────────────
# STEP 4 – Create category instances under NCF
# ─────────────────────────────────────────────
echo_step "Step 4: Create category instances under NCF"

for cat in board medium gradeLevel subject; do
  echo -n "  NCF/$cat... "
  R=$(curl -s -X POST "$BASE/framework/v3/category/create?framework=NCF" \
    -H "Content-Type: application/json" \
    -d "{\"request\":{\"category\":{\"name\":\"$cat\",\"code\":\"$cat\"}}}")
  check "$R"
done

# ─────────────────────────────────────────────
# STEP 5 – Create terms
# Each term identifier MUST be prefixed with the framework code (ncf_)
# so the FrameworkValidator can find them.
# ─────────────────────────────────────────────
echo_step "Step 5: Create terms – board"

create_term() {
  local framework="$1" category="$2" code="$3" name="$4"
  echo -n "    $code ($name)... "
  R=$(curl -s -X POST "$BASE/framework/v3/term/create?framework=$framework&category=$category" \
    -H "Content-Type: application/json" \
    -d "{\"request\":{\"term\":{\"code\":\"$code\",\"name\":\"$name\"}}}")
  check "$R"
}

# board
create_term NCF board ncf_cbse           "CBSE"
create_term NCF board ncf_icse           "ICSE"
create_term NCF board ncf_state_board    "State Board"
create_term NCF board ncf_igcse          "IGCSE"

# medium
echo_step "Step 5 (cont): terms – medium"
create_term NCF medium ncf_english  "English"
create_term NCF medium ncf_hindi    "Hindi"
create_term NCF medium ncf_tamil    "Tamil"
create_term NCF medium ncf_telugu   "Telugu"
create_term NCF medium ncf_kannada  "Kannada"
create_term NCF medium ncf_marathi  "Marathi"
create_term NCF medium ncf_bengali  "Bengali"
create_term NCF medium ncf_gujarati "Gujarati"
create_term NCF medium ncf_urdu     "Urdu"
create_term NCF medium ncf_odia     "Odia"

# gradeLevel
echo_step "Step 5 (cont): terms – gradeLevel"
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  create_term NCF gradeLevel "ncf_class_$i" "Class $i"
done

# subject
echo_step "Step 5 (cont): terms – subject"
create_term NCF subject ncf_english       "English"
create_term NCF subject ncf_hindi         "Hindi"
create_term NCF subject ncf_mathematics   "Mathematics"
create_term NCF subject ncf_science       "Science"
create_term NCF subject ncf_social_science "Social Science"
create_term NCF subject ncf_physics       "Physics"
create_term NCF subject ncf_chemistry     "Chemistry"
create_term NCF subject ncf_biology       "Biology"
create_term NCF subject ncf_history       "History"
create_term NCF subject ncf_geography     "Geography"
create_term NCF subject ncf_civics        "Civics"
create_term NCF subject ncf_economics     "Economics"
create_term NCF subject ncf_accountancy   "Accountancy"
create_term NCF subject ncf_business_studies "Business Studies"
create_term NCF subject ncf_computer_science "Computer Science"
create_term NCF subject ncf_environmental_science "Environmental Science"

# ─────────────────────────────────────────────
# STEP 6 – Publish the NCF framework
# ─────────────────────────────────────────────
echo_step "Step 6: Publish NCF framework"
R=$(curl -s -X POST "$BASE/framework/v3/publish/NCF" \
  -H "Content-Type: application/json" \
  -d '{}')
echo "  $R"

# ─────────────────────────────────────────────
# STEP 7 – Update Practice Question Set category definition
# ─────────────────────────────────────────────
echo_step "Step 7: Update Practice Question Set object category definition"
R=$(curl -s -X PATCH "$BASE/object/category/definition/v4/update/obj-cat:practice-question-set_questionset_all" \
  -H "Content-Type: application/json" \
  -d '{
    "request": {
        "objectCategoryDefinition": {
            "objectMetadata": {
                "config": {
                    "sourcingSettings": {
                        "collection": {
                            "maxDepth": 1,
                            "objectType": "QuestionSet",
                            "primaryCategory": "Practice Question Set",
                            "isRoot": true,
                            "iconClass": "fa fa-book",
                            "children": {},
                            "hierarchy": {
                                "level1": {
                                    "name": "Section",
                                    "type": "Unit",
                                    "mimeType": "application/vnd.sunbird.questionset",
                                    "primaryCategory": "Practice Question Set",
                                    "iconClass": "fa fa-folder-o",
                                    "children": {
                                        "Question": [
                                            "Multiple Choice Question",
                                            "Subjective Question"
                                        ]
                                    }
                                }
                            }
                        }
                    }
                },
                "schema": {
                    "properties": {
                        "mimeType": {
                            "type": "string",
                            "enum": ["application/vnd.sunbird.questionset"]
                        }
                    }
                }
            },
            "forms": {
                "childMetadata": {
                    "templateName": "",
                    "required": [],
                    "properties": [
                        {"code":"name","dataType":"text","editable":true,"inputType":"text","label":{"en":"Title"},"name":{"en":"Title"},"placeholder":{"en":"Title"},"renderingHints":{"class":"sb-g-col-lg-1 required"},"required":true,"visible":true,"validations":[{"type":"max","value":"100","message":{"en":"Input is Exceeded"}},{"type":"required","message":{"en":"Title is required"}}]},
                        {"code":"board","default":"","visible":true,"editable":false,"dataType":"text","renderingHints":{"class":"sb-g-col-lg-1"},"label":{"en":"Board/Syllabus"},"required":false,"name":{"en":"Board/Syllabus"},"inputType":"select","placeholder":{"en":"Select Board/Syllabus"}},
                        {"code":"medium","visible":true,"editable":true,"default":"","dataType":"list","renderingHints":{"class":"sb-g-col-lg-1"},"label":{"en":"Medium"},"required":false,"name":{"en":"Medium"},"inputType":"select","placeholder":{"en":"Select Medium"}},
                        {"code":"gradeLevel","visible":true,"editable":true,"default":"","dataType":"list","renderingHints":{"class":"sb-g-col-lg-1"},"label":{"en":"Class"},"required":false,"name":{"en":"Class"},"inputType":"select","placeholder":{"en":"Select Class"}},
                        {"code":"subject","visible":true,"editable":true,"default":"","dataType":"list","renderingHints":{"class":"sb-g-col-lg-1"},"label":{"en":"Subject"},"required":false,"name":{"en":"Subject"},"inputType":"select","placeholder":{"en":"Select Subject"}},
                        {"code":"maxScore","dataType":"number","editable":true,"inputType":"text","label":{"en":"Marks:"},"name":{"en":"Marks"},"placeholder":{"en":"Marks"},"renderingHints":{"class":"sb-g-col-lg-1 required"},"validations":[{"type":"pattern","value":"^[1-9]{1}[0-9]*$","message":{"en":"Input should be numeric"}},{"type":"required","message":{"en":"Marks is required"}}]}
                    ]
                },
                "create": {
                    "templateName": "",
                    "required": [],
                    "properties": [
                        {"name":{"en":"Basic details"},"fields":[
                            {"code":"appIcon","name":{"en":"Icon"},"label":{"en":"Icon"},"placeholder":{"en":"Icon"},"dataType":"text","inputType":"appIcon","editable":true,"required":true,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1 required"}},
                            {"code":"name","name":{"en":"Name"},"label":{"en":"Name"},"placeholder":{"en":"Name"},"dataType":"text","inputType":"text","editable":true,"required":true,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1 required"},"validations":[{"type":"max","value":"120","message":{"en":"Input is Exceeded"}},{"type":"required","message":{"en":"Name is required"}}]},
                            {"code":"description","name":{"en":"Description"},"label":{"en":"Description"},"placeholder":{"en":"Description"},"dataType":"text","inputType":"textarea","editable":true,"required":true,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1 required"},"validations":[{"type":"required","message":{"en":"description is required"}}]},
                            {"code":"keywords","name":{"en":"Keywords"},"label":{"en":"keywords"},"placeholder":{"en":"Enter Keywords"},"dataType":"list","inputType":"keywords","editable":true,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1"}},
                            {"code":"instructions","name":{"en":"Instructions"},"label":{"en":"Instructions"},"placeholder":{"en":"Enter Instructions"},"dataType":"text","inputType":"richtext","editable":true,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-2"},"validations":[{"type":"maxLength","value":"500","message":{"en":"Input is Exceeded"}}]},
                            {"code":"primaryCategory","name":{"en":"Type"},"label":{"en":"Type"},"placeholder":{"en":""},"dataType":"text","inputType":"text","editable":false,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1"}}
                        ]},
                        {"name":{"en":"Framework details"},"fields":[
                            {"code":"board","name":{"en":"Board/Syllabus"},"label":{"en":"Board/Syllabus"},"placeholder":{"en":"Select Board/Syllabus"},"default":"","dataType":"text","inputType":"select","editable":true,"required":true,"visible":true,"depends":[],"renderingHints":{"class":"sb-g-col-lg-1 required"},"validations":[{"type":"required","message":{"en":"Board is required"}}]},
                            {"code":"medium","name":{"en":"Medium"},"label":{"en":"Medium"},"placeholder":{"en":"Select Medium"},"default":"","dataType":"list","inputType":"select","editable":true,"required":true,"visible":true,"depends":["board"],"renderingHints":{"class":"sb-g-col-lg-1 required"},"validations":[{"type":"required","message":{"en":"Medium is required"}}]},
                            {"code":"gradeLevel","name":{"en":"Class"},"label":{"en":"Class"},"placeholder":{"en":"Select Class"},"default":"","dataType":"list","inputType":"select","editable":true,"required":true,"visible":true,"depends":["board","medium"],"renderingHints":{"class":"sb-g-col-lg-1 required"},"validations":[{"type":"required","message":{"en":"Class is required"}}]},
                            {"code":"subject","name":{"en":"Subject"},"label":{"en":"Subject"},"placeholder":{"en":"Select Subject"},"default":"","dataType":"list","inputType":"select","editable":true,"required":true,"visible":true,"depends":["board","medium","gradeLevel"],"renderingHints":{"class":"sb-g-col-lg-1 required"},"validations":[{"type":"required","message":{"en":"Subject is required"}}]},
                            {"code":"audience","name":{"en":"Audience"},"label":{"en":"Audience"},"placeholder":{"en":"Select Audience"},"dataType":"list","inputType":"select","editable":true,"required":true,"visible":true,"range":["Student","Teacher","Administrator"],"renderingHints":{"class":"sb-g-col-lg-1 required"},"validations":[{"type":"required","message":{"en":"Audience is required"}}]}
                        ]},
                        {"name":{"en":"Question set behaviour"},"fields":[
                            {"code":"maxTime","name":{"en":"MaxTimer"},"label":{"en":"Set Maximum Time"},"placeholder":{"en":"HH:mm:ss"},"default":"3600","dataType":"text","inputType":"timer","editable":true,"required":true,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1"},"validations":[{"type":"time","message":{"en":"Please enter in hh:mm:ss"},"value":"HH:mm:ss"},{"type":"max","value":"05:59:59","message":{"en":"max time should be less than 05:59:59"}}]},
                            {"code":"showTimer","name":{"en":"show Timer"},"label":{"en":"show Timer"},"placeholder":{"en":"show Timer"},"default":false,"dataType":"boolean","inputType":"checkbox","editable":true,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1"}},
                            {"code":"requiresSubmit","name":{"en":"Submit Confirmation"},"label":{"en":"Submit Confirmation Page"},"placeholder":{"en":"Select Submit Confirmation"},"dataType":"text","inputType":"select","output":"identifier","range":[{"identifier":"Yes","label":"Enable"},{"identifier":"No","label":"Disable"}],"editable":true,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1"}},
                            {"code":"maxAttempts","name":{"en":"Max Attempts"},"label":{"en":"Max Attempts"},"placeholder":{"en":"Max Attempts"},"dataType":"number","inputType":"select","editable":true,"required":false,"visible":true,"range":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25],"renderingHints":{"class":"sb-g-col-lg-1"}},
                            {"code":"summaryType","name":{"en":"summaryType"},"label":{"en":"Summary Type"},"placeholder":{"en":"Select Summary Type"},"dataType":"text","inputType":"select","editable":true,"required":false,"visible":true,"range":["Complete","Score","Duration","Score and Duration"],"renderingHints":{"class":"sb-g-col-lg-1"}}
                        ]}
                    ]
                },
                "search": {
                    "templateName": "",
                    "required": [],
                    "properties": [
                        {"code":"primaryCategory","dataType":"list","editable":true,"default":[],"renderingHints":{"class":"sb-g-col-lg-1"},"inputType":"nestedselect","label":{"en":"Question Type(s)"},"name":{"en":"Type"},"placeholder":{"en":"Select QuestionType"},"required":false,"visible":true},
                        {"code":"board","visible":true,"depends":[],"editable":true,"dataType":"list","label":{"en":"Board"},"required":false,"name":{"en":"Board"},"inputType":"select","placeholder":{"en":"Select Board"},"output":"name","renderingHints":{"class":"sb-g-col-lg-1"}},
                        {"code":"medium","visible":true,"editable":true,"dataType":"list","label":{"en":"Medium(s)"},"required":false,"name":{"en":"Medium"},"inputType":"nestedselect","placeholder":{"en":"Select Medium"},"output":"name","depends":["board"],"renderingHints":{"class":"sb-g-col-lg-1"}},
                        {"code":"gradeLevel","visible":true,"depends":["board","medium"],"editable":true,"default":"","dataType":"list","renderingHints":{"class":"sb-g-col-lg-1"},"label":{"en":"Class(es)"},"required":false,"name":{"en":"Class"},"inputType":"nestedselect","placeholder":{"en":"Select Class"},"output":"name"},
                        {"code":"subject","visible":true,"depends":["board","medium","gradeLevel"],"editable":true,"default":"","dataType":"list","renderingHints":{"class":"sb-g-col-lg-1"},"label":{"en":"Subject(s)"},"required":false,"name":{"en":"Subject"},"inputType":"nestedselect","placeholder":{"en":"Select Subject"},"output":"name"}
                    ]
                },
                "unitMetadata": {
                    "templateName": "",
                    "required": [],
                    "properties": [
                        {"code":"name","dataType":"text","editable":true,"inputType":"text","label":{"en":"Title"},"name":{"en":"Title"},"placeholder":{"en":"Title"},"renderingHints":{"class":"sb-g-col-lg-1 required"},"required":true,"visible":true,"validations":[{"type":"max","value":"120","message":{"en":"Input is Exceeded"}},{"type":"required","message":{"en":"Title is required"}}]},
                        {"code":"description","dataType":"text","editable":true,"inputType":"textarea","label":{"en":"Description"},"name":{"en":"Description"},"placeholder":{"en":"Description"},"renderingHints":{"class":"sb-g-col-lg-1 required"},"required":true,"visible":true,"validations":[{"type":"max","value":"500","message":{"en":"Input is Exceeded"}}]},
                        {"code":"instructions","name":{"en":"Instructions"},"label":{"en":"Instructions"},"placeholder":{"en":"Enter Instructions"},"dataType":"text","inputType":"richtext","editable":true,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-2 required"},"validations":[{"type":"maxLength","value":"500","message":{"en":"Input is Exceeded"}}]},
                        {"code":"maxQuestions","name":{"en":"Show Questions"},"label":{"en":"Count of questions to be displayed in this section"},"placeholder":{"en":"Input count of questions to be displayed"},"default":"","dataType":"number","inputType":"select","editable":true,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1"}},
                        {"code":"shuffle","name":{"en":"Shuffle Questions"},"label":{"en":"Shuffle Questions"},"placeholder":{"en":"Shuffle Questions"},"default":"false","dataType":"boolean","inputType":"checkbox","editable":true,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1"}},
                        {"code":"showFeedback","name":{"en":"Show Feedback"},"label":{"en":"Show Question Feedback"},"placeholder":{"en":"Select Option"},"dataType":"boolean","inputType":"checkbox","editable":true,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1"}},
                        {"code":"showSolutions","name":{"en":"Show Solution"},"label":{"en":"Show Solution"},"placeholder":{"en":"Select Option"},"dataType":"boolean","inputType":"checkbox","editable":true,"required":false,"visible":true,"renderingHints":{"class":"sb-g-col-lg-1"}}
                    ]
                }
            }
        }
    }
}')
check "$R"

echo
echo "Done. NCF framework seeded. You can now re-run your assessment item create request."
