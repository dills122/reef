package market

type Market struct {
	unfufilledOrders []order
	oderHistory      []FulfilledOrder
	equityBook       EquityBook
}
