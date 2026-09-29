# STERNA-RULES-V1: [{"name":"BANC148","enabled":true,"field":"FROM","match":"CONTAINS","value":"banc148","moveTo":null,"markRead":true,"flag":false}]
require ["imap4flags"];

if address :contains "from" "banc148" {
    addflag "\\Seen";
}
