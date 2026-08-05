package org.sunbird.qti

object QtiConstants {
  // QTI 3.0 resource types
  val QTI_ITEM_V3_TYPE = "imsqti_item_xmlv3p0"
  val QTI_TEST_V3_TYPE = "imsqti_test_xmlv3p0"

  // Interaction types
  val CHOICE_INTERACTION = "qti-choice-interaction"
  val MATCH_INTERACTION = "qti-match-interaction"
  val ASSOCIATE_INTERACTION = "qti-associate-interaction"
  val ORDER_INTERACTION = "qti-order-interaction"
  val TEXT_ENTRY_INTERACTION = "qti-text-entry-interaction"
  val EXTENDED_TEXT_INTERACTION = "qti-extended-text-interaction"
  val HOTTEXT_INTERACTION = "qti-hottext-interaction"
  val GAP_MATCH_INTERACTION = "qti-gap-match-interaction"
  val INLINE_CHOICE_INTERACTION = "qti-inline-choice-interaction"
  val HOTSPOT_INTERACTION = "qti-hotspot-interaction" // representative of the graphic/hotspot/drawing family
  val SLIDER_INTERACTION = "qti-slider-interaction"
  val UPLOAD_INTERACTION = "qti-upload-interaction"

  val PASSTHROUGH_INTERACTIONS: Set[String] = Set(
    HOTTEXT_INTERACTION, GAP_MATCH_INTERACTION, INLINE_CHOICE_INTERACTION,
    HOTSPOT_INTERACTION, SLIDER_INTERACTION, UPLOAD_INTERACTION
  )

  // PrimaryCategory values — must match registered obj-cat definitions (scripts/definition-scripts/master_category_create)
  val MCQ = "Multiple Choice Question"
  val BOOLEAN = "Multiple Choice Question"
  val SA = "Subjective Question"
  val FTB = "FTB Question"
  val MTF = "Match The Following Question"
  val SEQ = "Sequence Question"
  val REO = "Reorder Question"

  // PrimaryCategory values — must match new obj-cat definitions
  val QTI_HOTTEXT = "Hot Text Question"
  val QTI_GAP_MATCH = "Gap Match Question"
  val QTI_INLINE_CHOICE = "Inline Choice Question"
  val QTI_HOTSPOT = "Hotspot Question"
  val QTI_UPLOAD = "Upload Question"
  val QTI_SLIDER = "Slider Question"

  // Error codes
  val ERR_INVALID_PACKAGE = "ERR_INVALID_PACKAGE"
  val ERR_INVALID_MANIFEST = "ERR_INVALID_MANIFEST"
  val ERR_UNSUPPORTED_INTERACTION = "ERR_UNSUPPORTED_INTERACTION"
  val ERR_INVALID_ITEM = "ERR_INVALID_ITEM"
  val ERR_MEDIA_REFERENCE = "ERR_MEDIA_REFERENCE"
  val ERR_IMPORT_FAILED = "ERR_IMPORT_FAILED"
}
