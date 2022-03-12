package market

type order struct {
	id            string
	orderType     string
	equitySymbol  string
	orderQuantity float32
	pricePoint    float32
	timestamp     string
	//TODO maybe add trader Id here
}
