require ["fileinto","imap4flags","vacation"];
# rule:[Spam]
if header :contains "subject" "[SPAM]"
{
	fileinto "Junk";
	stop;
}
# rule:[boss]
if address :is "from" "boss@example.com"
{
	addflag "\\Flagged";
}
# rule:[either]
if anyof (address :is "from" "a@example.com", header :contains "subject" "urgent")
{
	fileinto "Work";
}
# rule:[away]
if true
{
	vacation :days 7 :subject "Absent" text:
Bonjour,

Je suis absent jusqu'au 12 {septembre} "vraiment".
..un point double en tete
.
;
}
