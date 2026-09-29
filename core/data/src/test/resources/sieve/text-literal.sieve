require ["vacation", "fileinto"];
/* a block comment
   with an } unbalanced brace and a " quote */
vacation :subject "Out" text: # the body
Line one; with a semicolon
if false { stop; }
..
.
;

if header :is "subject" "a \"quoted\" \\ value" { fileinto "Odd"; }   # trailing
keep; stop;
# a comment after the last command, no final newline