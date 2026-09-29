# roundcube filter script
require ["fileinto"];

if header :contains "subject" "roundcube" {
    fileinto "Archive";
}
