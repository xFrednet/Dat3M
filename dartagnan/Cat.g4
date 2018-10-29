grammar Cat;
@header{
    package dartagnan;
    import dartagnan.wmm.axiom.*;
}

mcm
    :   (NAME)? definition+ EOF
    ;

definition
    :   axiomDefinition
    |   letDefinition
    |   letRecDefinition
    ;

axiomDefinition locals [Class<?> cls]
    :   (negate = NOT)? ACYCLIC { $cls = Acyclic.class; } e = expression (AS NAME)?
    |   (negate = NOT)? IRREFLEXIVE { $cls = Irreflexive.class; } e = expression (AS NAME)?
    |   (negate = NOT)? EMPTY { $cls = Empty.class; } e = expression (AS NAME)?
    ;

letDefinition
    :   LET n = NAME EQ e = expression
    ;

letRecDefinition
    :   LET REC n = NAME EQ e = expression letRecAndDefinition*
    ;

letRecAndDefinition
    :   AND n = NAME EQ e = expression
    ;

expression
    :   e1 = expression STAR e2 = expression                            # exprCartesian
    |   e = expression (POW)? STAR                                      # exprTransRef
    |   e = expression (POW)? PLUS                                      # exprTransitive
    |   e = expression (POW)? INV                                       # exprInverse
    |   e = expression OPT                                              # exprOptional
    |   NOT e = expression                                              # exprComplement
    |   e1 = expression SEMI e2 = expression                            # exprComposition
    |   e1 = expression BAR e2 = expression                             # exprUnion
    |   e1 = expression BSLASH e2 = expression                          # exprMinus
    |   e1 = expression AMP e2 = expression                             # exprIntersection
    |   (TOID LPAR e = expression RPAR | LBRAC e = expression RBRAC)    # exprIdentity
    |   FENCEREL LPAR n = NAME RPAR                                     # exprFencerel
    |   LPAR e = expression RPAR                                        # expr
    |   n = NAME                                                        # exprBasic
    ;

LET     :   'let';
REC     :   'rec';
AND     :   'and';
AS      :   'as';
TOID    :   'toid';

ACYCLIC     :   'acyclic';
IRREFLEXIVE :   'irreflexive';
EMPTY       :   'empty';

EQ      :   '=';
STAR    :   '*';
PLUS    :   '+';
OPT     :   '?';
INV     :   '-1';
NOT     :   '~';
AMP     :   '&';
BAR     :   '|';
SEMI    :   ';';
BSLASH  :   '\\';
POW     :   ('^');

LPAR    :   '(';
RPAR    :   ')';
LBRAC   :   '[';
RBRAC   :   ']';

FENCEREL    :   'fencerel';

NAME    : [A-Za-z0-9\-_]+;

LINE_COMMENT
    :   '//' ~[\n]*
        -> skip
    ;

BLOCK_COMMENT
    :   '(*' (.)*? '*)'
        -> skip
    ;

WS
    :   [ \t\r\n]+
        -> skip
    ;

INCLUDE
    :   'include "' .*? '"'
        -> skip
    ;

MODELNAME
    :   '"' .*? '"'
        -> skip
    ;