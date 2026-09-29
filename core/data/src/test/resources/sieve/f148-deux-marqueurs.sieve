# STERNA-RULES-V1: [{"name":"BANC148","enabled":true,"field":"FROM","match":"CONTAINS","value":"banc148","moveTo":null,"markRead":true,"flag":false}]
require ["imap4flags"];

if address :contains "from" "banc148" {
    addflag "\\Seen";
}

# STERNA-RULES-V1: [{"name":"SECOND","enabled":true,"field":"FROM","match":"CONTAINS","value":"second","moveTo":null,"markRead":true,"flag":false}]
