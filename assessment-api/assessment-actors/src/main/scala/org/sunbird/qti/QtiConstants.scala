package org.sunbird.qti

object QtiConstants {
  // QTI 3.0 resource types
  val QTI_ITEM_V3_TYPE = "imsqti_item_xmlv3p0"
  val QTI_TEST_V3_TYPE = "imsqti_test_xmlv3p0"

  // Interaction types (Phase 1 targets) — QTI 3.0 kebab-case element names
  val CHOICE_INTERACTION = "qti-choice-interaction"
  val MATCH_INTERACTION = "qti-match-interaction"
  val ASSOCIATE_INTERACTION = "qti-associate-interaction"
  val ORDER_INTERACTION = "qti-order-interaction"
  val TEXT_ENTRY_INTERACTION = "qti-text-entry-interaction"
  val EXTENDED_TEXT_INTERACTION = "qti-extended-text-interaction"

  // Phase 1 primaryCategory values — must match registered obj-cat definitions (scripts/definition-scripts/master_category_create)
  val MCQ = "Multiple Choice Question"
  val BOOLEAN = "Multiple Choice Question" 
  val SA = "Subjective Question"
  val FTB = "FTB Question"
  val MTF = "Match The Following Question"
  val SEQ = "Sequence Question"
  val REO = "Reorder Question"

  // Error codes
  val ERR_INVALID_PACKAGE = "ERR_INVALID_PACKAGE"
  val ERR_INVALID_MANIFEST = "ERR_INVALID_MANIFEST"
  val ERR_UNSUPPORTED_INTERACTION = "ERR_UNSUPPORTED_INTERACTION"
  val ERR_INVALID_ITEM = "ERR_INVALID_ITEM"
  val ERR_MEDIA_REFERENCE = "ERR_MEDIA_REFERENCE"
  val ERR_IMPORT_FAILED = "ERR_IMPORT_FAILED"
}
