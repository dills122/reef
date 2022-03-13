package market

import "github.com/google/uuid"

type Market struct {
	unfufilledOrders []Order
	oderHistory      []FulfilledOrder
	equityBook       EquityBook
}

const GrowSize = 500

func New(
	name string,
	equityBook EquityBook,
) Market {
	u := make([]Order, GrowSize)
	o := make([]FulfilledOrder, GrowSize)
	return Market{unfufilledOrders: u, oderHistory: o}
}

/*******************************
***********Public APIs**********
********************************/

func (market *Market) ExecuteOrder(order Order) {
	order.id = uuid.NewString()
	//Need method to ensure no dup orders were submitted
	market.unfufilledOrders = append(market.unfufilledOrders, order)
	//should have return type based on success/fail
}

func (market *Market) CancelOrder(orderId string) {

}
