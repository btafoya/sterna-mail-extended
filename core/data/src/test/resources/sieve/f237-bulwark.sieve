/* @metadata:begin
{"version":1,"rules":[{"id":"b237-1","name":"Newsletters","enabled":true,"matchType":"all","conditions":[{"field":"from","comparator":"contains","value":"news@"}],"actions":[{"type":"move","value":"Archive"}],"stopProcessing":false},{"id":"b237-2","name":"Boss","enabled":true,"matchType":"all","conditions":[{"field":"subject","comparator":"is","value":"URGENT"}],"actions":[{"type":"star"},{"type":"mark_read"}],"stopProcessing":false},{"id":"b237-3","name":"Big or invoice","enabled":true,"matchType":"any","conditions":[{"field":"size","comparator":"greater_than","value":"5M"},{"field":"body","comparator":"contains","value":"invoice"}],"actions":[{"type":"discard"}],"stopProcessing":false},{"id":"b237-4","name":"Sleeping","enabled":false,"matchType":"all","conditions":[{"field":"cc","comparator":"contains","value":"sleep@"}],"actions":[{"type":"discard"}],"stopProcessing":false}]}
@metadata:end */

require ["body", "comparator-i;ascii-numeric", "fileinto", "imap4flags", "relational", "spamtestplus"];

# Rule: Newsletters
if allof(header :contains "From" "news@", not spamtest :percent :value "ge" :comparator "i;ascii-numeric" "50") {
    fileinto "Archive";
}

# Rule: Boss
if header :is "Subject" "URGENT" {
    addflag "\\Flagged";
    addflag "\\Seen";
}

# Rule: Big or invoice
if anyof(size :over 5M, body :contains "invoice") {
    discard;
}

# --- External rules (managed outside Bulwark) ---
# rule:[Roundcube one]
if header :contains "subject" "roundcube" {
    fileinto "Archive";
}

# rule:[Roundcube two]
if header :contains "subject" "rc2" {
    fileinto "Archive";
    stop;
}
