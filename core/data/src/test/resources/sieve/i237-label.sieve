/* @metadata:begin
{"version":1,"rules":[{"id":"6724bc1b-6a6c-4050-a91a-5b59fb998a8b","name":"Test","enabled":true,"matchType":"all","conditions":[{"field":"subject","comparator":"contains","value":"TESTSIEVE"}],"actions":[{"type":"move","value":"Test","mailboxId":"o"},{"type":"mark_read"},{"type":"add_label","value":"red"}],"stopProcessing":false}]}
@metadata:end */

require ["comparator-i;ascii-numeric", "fileinto", "imap4flags", "mailbox", "mailboxid", "relational", "spamtestplus"];

# Rule: Test
if allof(header :contains "Subject" "TESTSIEVE", not spamtest :percent :value "ge" :comparator "i;ascii-numeric" "50") {
    addflag "\\Seen";
    addflag "$label:red";
    fileinto :mailboxid "o" "Test";
}
