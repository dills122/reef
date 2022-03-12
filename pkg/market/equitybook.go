package market

type EquityBook struct {
	entries   []EquityBookEntry
	createdAt string
}

type EquityBookEntry struct {
	id               string
	symbol           string
	totalCirculation uint64
}
