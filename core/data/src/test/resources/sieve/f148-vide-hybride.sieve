# STERNA-RULES-V1: []
require ["fileinto"];

if header :contains "subject" "manuel" {
    fileinto "Archive";
}
