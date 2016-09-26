define(function(require, exports, module) {
   "use strict";

   var oop = require("../lib/oop");
   var TextHighlightRules = require("./text_highlight_rules").TextHighlightRules;
   var JSONTLHighlightRules = function() {
      var keywords = ("transform|column|columns|union|to|apply|assign|name|from|map|let|if|then|and|or|not|exists|like|join");
      var builtinFunctions = ("toStandardDate|toStandardTime|toStandardDateTime");
      var keywordMapper = this.createKeywordMapper({
         "support.function": builtinFunctions,
         "keyword": keywords},"identifier", true);
      this.$rules = {
         "start" : [
             { token: "comment",
               start: "/\\*", end: "\\*/"
             },
             { token: "string", // " string
               regex: '".*?"'
             },
             { token: keywordMapper,
               regex: "[a-zA-Z][a-zA-Z]*\\b"
             },
             { token: "keyword.operator",
               regex: ";|\\{\\{|\\}\\}|,|=|<|>"
             },
             { token: "paren.lparen",
               regex: "[\\(]"
             },
             { token: "paren.rparen",
               regex: "[\\)]"
             }
         ]
      };
      this.normalizeRules();
   };
   oop.inherits(JSONTLHighlightRules, TextHighlightRules);
   exports.JSONTLHighlightRules = JSONTLHighlightRules;
});

