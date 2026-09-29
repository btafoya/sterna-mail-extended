# STERNA-RULES-V1: {oops
require ["fileinto"];

if header :contains "subject" "banc" {
    fileinto "Archive";
}
