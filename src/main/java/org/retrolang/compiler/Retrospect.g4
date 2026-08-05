grammar Retrospect;

@header {
  import org.retrolang.util.Bits;
}

// A compilable unit is an optional sequence of statements followed by
// declarations (if present, the statements should end with a "return", but
// that is not required by the grammar).
// The statements may only be present in a request, not in a source file.
unit
  : block (decl separator)* EOF
  ;

// Compiler.atBreak() is true if the previous token was a line break
// or the next token is '}'
separator
  : ';'
  | { Compiler.atBreak($ctx) }?
  ;

// A block is a sequence of statements, each terminated by a semicolon,
// line break, or '}'.
block
  : (statement separator)*
  ;

// A decl may declare a new function, a method for an existing function, or
// a new type defined as a singleton, a union, or a compound.  New types may
// be subtypes or supertypes of existing types, but not both.
decl
  : ('open' | 'private')? kind=('function' | 'procedure')
    name=lowerId '(' paramList? ')' methodDef?                #functionDecl
  | 'method' name=functionName '(' paramList? ')'
    ('(' methodPredicate ')')?
    isDefault='default'?
    methodDef                                                 #methodDecl
  | 'private'? kind=('singleton' | 'compound') UPPER_ID
    ('is' typeList)?                                          #leafTypeDecl
  | ('open' | 'private')? 'type' UPPER_ID
    (marker=('is' | 'contains') typeList)?                    #unionTypeDecl
  ;

typeList
  : typeName (',' typeName)*
  ;

paramList
  : param (',' param)*
  ;

methodPredicate
  : negation='not'? '(' methodPredicate ')'                #parenMethodPredicate
  | lowerId 'is' negation='not'? typeName                  #argMethodPredicate
  | left=methodPredicate  op=('and' | 'or')
    right=methodPredicate                                  #conjMethodPredicate
;

// Methods may be defined by a block or just an expression.
methodDef
  : '{' block '}'
  | '=' expression
  ;

// Parameters (in function or method declarations) can be like the left hand
// side of an extractStatement, or a type and optional id.  Either form may
// be followed by "=" or "<<".
param
  : (extractLhs | typeName lowerId?) paramAnnotation?
  ;

paramAnnotation
  : '='
  | '<<'
  ;

// Note that this grammar is ambigous; "x = a" can be parsed as either an
// assignmentStatement (which would also include e.g. "x += a") or as
// an extractStatement (which would also include e.g. "[x, y] = a").
// ANTLR doesn't complain (I think it chooses assignmentStatement since it
// occurs first), and either one would work so I don't think it's worth
// complicating the grammar to avoid the ambiguity.
statement
  : lowerId index* assignOp expression                   #assignmentStatement
  | functionCall                                         #functionCallStatement
  | lowerId '<<' dist='^'? expression                    #emitStatement
  | extractLhs '=' expression                            #extractStatement
  | 'return' expression?                                 #returnStatement
  | 'assert' expression (',' STRING)? (',' lowerId)*     #assertStatement
  | 'trace' ((lowerId | STRING) (',' lowerId)*)?         #traceStatement
  | forLoop                                              #forStatement
  | breakStmt                                            #breakStatement
  | 'continue'                                           #continueStatement
  | 'if' expression '{' ifBlock=block '}'
    elseIf*
    ('else' '{' elseBlock=block '}')?                    #ifStatement
  ;

// Each forLoop node is linked to a corresponding Loop object.
forLoop locals [Loop loop]
  : 'for'
    ((key=extractLhs ':')?  value=extractLhs 'in' expression)?
    (seq='sequential' (seqIds+=lowerId (',' seqIds+=lowerId)*)?)?
    '{' block '}'
    ('collect' '{' (collectVar separator)* '}')?
  ;

// Each breakStmt node is linked to a corresponding Loop.Break.
breakStmt locals [Loop.Break loopBreak]
  : 'break' ('{' block '}')?
  ;

// The left hand side of an assignment may be indexed one or more times, using
// any combination of array access notation, field access notation, or the "_"
// (uncompound) postfix operator.
index
  : '[' (arrayElement (',' arrayElement)*)? ']'
  | '@' dist='^'? expression
  | '.' lowerId
  | '_'
  ;

assignOp
   : '='
   | '+='
   | '-='
   | '*='
   | '/='
   | '&='
   | '|='
   ;

// The left hand side of an extractStatement
extractLhs
  : lowerId                                                    #simpleExtract
  | '[' extractLhs (',' extractLhs)* ']'                       #arrayExtract
  | '{' extractStructElement (',' extractStructElement)* '}'   #structExtract
  | '_'                                                        #discardExtract
  ;

// Struct keys may be ids or strings.  If ids, the value may be omitted and
// is assumed to be the variable with the same name.
extractStructElement
  : lowerId (':' extractLhs)?
  | STRING ':' extractLhs
  ;

functionCall
  : functionName '(' (arg (',' arg)*)? ')'
  ;

// Arguments in function calls are expressions, optionally followed by an "="
// or "<<" annotation (not all expressions may be annotated, but we don't try
// to detect that in the grammar).
arg
  : dist='^'? expression paramAnnotation?
  ;

collectVar
  : ids+=lowerId (',' ids+=lowerId)* ('=|' expression)?
  ;

elseIf
  : 'else' 'if' expression '{' block '}'
  ;

expression
  : '(' expression ')'                                   #parenExpression
  | typeName ('_' '(' expression ')')?                   #typeNameExpression
  | expression dist='^'? index                           #indexExpression
  | op=('-' | 'not') rdist='^'? right=expression         #opExpression
  | left=expression ldist='^'?
    op='**'
    rdist='^'? right=expression                          #opExpression
  | left=expression ldist='^'?
    op=('*' | '/' | '%')
    rdist='^'? right=expression                          #opExpression
  | left=expression ldist='^'?
    op=('+' | '-')
    rdist='^'? right=expression                          #opExpression
  | left=expression ldist='^'?
    op=('..' | '++')
    rdist='^'? right=expression                          #opExpression
  | left=expression ldist='^'? op='..'                   #opExpression
  | op='..' rdist='^'? right=expression                  #opExpression
  | op='..'                                              #opExpression
  | left=expression ldist='^'?
    op='&'
    rdist='^'? right=expression                          #opExpression
  | left=expression ldist='^'?
    op=('>=' | '<=' | '<' | '>' | '==' | '!=')
    rdist='^'? right=expression                          #opExpression
  | expression dist='^'? 'is' negation='not'? typeName   #typeCheckExpression
  | '#'                                                  #hashExpression
  | extractLhs? lambdaArrow expression                   #lambdaExpression
  | left=expression ldist='^'?
    op='|'
    rdist='^'? right=expression                          #opExpression
  | left=expression  op=('and' | 'or') right=expression  #conjExpression
  | cond=expression '?'
    ifTrue=expression? ':' ifFalse=expression            #ternaryExpression
  | functionCall                                         #functionCallExpression
  | '[' (arrayElement (',' arrayElement)*)? ']'          #arrayExpression
  | '{' (structElement (',' structElement)*)? '}'        #structExpression
  | functionName                                         #idRefExpression
  | NUMBER                                               #numberExpression
  | STRING                                               #stringExpression
  ;

// Each lambdaArrow node is linked to a corresponding BlockCompiler object.
// (That's the reason to make it a named node rather than just use the token.)
lambdaArrow locals [BlockCompiler blockCompiler]
  : '->'
  ;

arrayElement
  : dist='^'? expression
  ;

structElement
  : lowerId (':' dist='^'? expression)?
  | STRING ':' dist='^'? expression
  ;

typeName
  : UPPER_ID ('.' localName=UPPER_ID)?
  ;

functionName
  : (module=UPPER_ID '.')? lowerId
  ;

// Each lowerId node is linked to a corresponding Scope.Entry.
//
// Note that keywords are not included in LOWER_ID.  It is possible to add
// individual keywords as alternatives here in cases where that wouldn't create
// an ambiguity (e.g. 'collect', 'contains', 'default', 'open', and/or 'private'
// would probably be OK).
lowerId locals [Scope.Entry entry]
  : LOWER_ID
  ;

NUMBER
  : (DIGIT+ ('.' DIGIT+)? | '.' DIGIT+) ([eE] [+-]? DIGIT+)?
  ;

// LOWER_IDs may have embedded "_"s, but not start or end with one.
// Don't use this directly, use lowerId instead.
LOWER_ID
  : [a-z] ('_'* [a-zA-Z0-9])*
  ;

// UPPER_IDs start with an upper case letter and identify a type or module.
// Like LOWER_IDs they may have embedded "_"s, but not start or end with one.
UPPER_ID
  : [A-Z] ('_'* [a-zA-Z0-9])*
  ;

STRING
  : ["] (~["\r\n\\] | '\\' (["'btnfr\\] | 'u' HEX HEX HEX HEX))* ["]
  ;

COMMENT
  : ('//' ~[\r\n]* | '/*' .*? '*/') -> skip
  ;

WS
  : [ \t\u000C] -> skip
  ;

// Sending newline chars to a hidden channel means they're skipped (like
// other whitespace) but can still be checked for by Compiler.atBreak()
NL
  : [\r\n]+ -> channel(HIDDEN)
  ;

fragment DIGIT : [0-9] ;
fragment HEX : [0-9a-fA-F] ;
