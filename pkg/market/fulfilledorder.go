package market

type FulfilledOrder struct {
	id                 string
	equitySymbol       string
	pricePoint         float32
	associatedOrderIds []string
	timestamp          string
}
