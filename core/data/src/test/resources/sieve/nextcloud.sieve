### Nextcloud Mail: Filters ### DON'T EDIT ###
require ["fileinto"];
### Nextcloud Mail: Filters ### DON'T EDIT ###
# Hello, this is a test
### Nextcloud Mail: Filters ### DON'T EDIT ###
# FILTER: [{"name":"Invoices","enable":true,"operator":"allof","tests":[{"operator":"contains","values":["invoice"],"field":"subject"}],"actions":[{"type":"fileinto","mailbox":"Invoices"}],"priority":10}]
# Invoices
if header :contains "subject" "invoice" {
fileinto "Invoices";
}
### Nextcloud Mail: Filters ### DON'T EDIT ###
# mine, below the region
if header :contains "subject" "mine" {
    fileinto "Mine";
}
