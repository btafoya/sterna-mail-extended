require ["fileinto"];

# ecrit a la main, sans marqueur Sterna
if header :contains "subject" "banc" {
    fileinto "Archive";
}
