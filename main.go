package main

import (
	"fmt"
	"log"
	"os"
	"strconv"
	"time"

	"github.com/suborbital/grav/discovery/local"
	"github.com/suborbital/grav/grav"
	ghttp "github.com/suborbital/grav/transport/http"
	"github.com/suborbital/vektor/vk"
	"github.com/suborbital/vektor/vlog"
)

func main() {
	anotherTest()
}

type TestNode struct {
	GravPort string
	HttpPort int
}

func anotherTest() {

	var testNodes [2]TestNode

	testNodes[0] = TestNode{GravPort: "5555", HttpPort: 4011}
	testNodes[1] = TestNode{GravPort: "5555", HttpPort: 4012}

	logger := vlog.Default(vlog.Level(vlog.LogLevelDebug))
	gravhttp := ghttp.New()
	locald := local.New()

	var testNode TestNode

	if len(os.Args) >= 1 {
		if s, err := strconv.ParseInt(os.Args[1], 10, 32); err == nil {
			testNode = testNodes[int(s)]
		}
	}

	g := grav.New(
		grav.UseLogger(logger),
		grav.UseEndpoint(testNode.GravPort, ""),
		grav.UseTransport(gravhttp),
		grav.UseDiscovery(locald),
	)

	pod := g.Connect()
	pod.On(func(msg grav.Message) error {
		fmt.Println("received something:", string(msg.Data()))
		return nil
	})

	vk := vk.New(
		vk.UseAppName("http tester"),
		vk.UseHTTPPort(testNode.HttpPort),
	)
	vk.POST("/meta/message", gravhttp.HandlerFunc())

	go func() {
		<-time.After(time.Second * time.Duration(10))
		pod.Send(grav.NewMsg(grav.MsgTypeDefault, []byte("hello, world from: "+g.NodeUUID)))
	}()

	if err := vk.Start(); err != nil {
		log.Fatal(err)
	}
}

// func networkNodeTest() {
// 	logger := vlog.Default(vlog.Level(vlog.LogLevelInfo))
// 	gwss := websocket.New()
// 	locald := local.New()

// 	var port int

// 	// var belongsTo string
// 	// var id string
// 	// cap := "not-sure"
// 	if len(os.Args) > 3 {
// 		// belongsTo = os.Args[1]
// 		// // cap = os.Args[2]
// 		// id = os.Args[2]
// 		if s, err := strconv.ParseInt(os.Args[3], 10, 32); err == nil {
// 			port = int(s)
// 		}
// 	}

// 	g := grav.New(
// 		grav.UseLogger(logger),
// 		grav.UseEndpoint("55555", ""),
// 		grav.UseTransport(gwss),
// 		grav.UseDiscovery(locald),
// 		// grav.UseBelongsTo(belongsTo),
// 		// grav.UseInterests(cap),
// 	)

// 	id := g.NodeUUID

// 	podOne := g.Connect()
// 	podOne.On(func(msg grav.Message) error {
// 		fmt.Println("Pod One: ", id, " received something:", string(msg.Data()))
// 		return nil
// 	})

// 	podTwo := g.Connect()
// 	podTwo.On(func(msg grav.Message) error {
// 		fmt.Println("Pod Two: ", id, " received something:", string(msg.Data()))
// 		return nil
// 	})

// 	vk := vk.New(
// 		vk.UseAppName("websocket tester "+id),
// 		vk.UseHTTPPort(port),
// 	)
// 	vk.HandleHTTP(http.MethodGet, "/meta/message", gwss.HTTPHandlerFunc())

// 	go func() {
// 		<-time.After(time.Second * time.Duration(5))
// 		podOne.Send(grav.NewMsg(grav.MsgTypeDefault, []byte("hello, world")))
// 		<-time.After(time.Second * time.Duration(1))
// 		podTwo.Send(grav.NewMsg(grav.MsgTypeDefault, []byte("hello, world")))
// 		<-time.After(time.Second * time.Duration(2))
// 	}()

// 	if err := vk.Start(); err != nil {
// 		log.Fatal(err)
// 	}
// }

// func localMultiNodeTest() {
// 	g := grav.New()

// 	// create a pod and set a function to be called on each message
// 	p := g.Connect()
// 	p.On(func(msg grav.Message) error {
// 		fmt.Println("Pod 1: message received:", string(msg.Data()))

// 		return nil
// 	})

// 	// create a second pod and send a message
// 	p2 := g.Connect()
// 	p2.On(func(msg grav.Message) error {
// 		fmt.Println("Pod 2: message received:", string(msg.Data()))

// 		return nil
// 	})

// 	p3 := g.Connect()
// 	p3.On(func(msg grav.Message) error {
// 		fmt.Println("Pod 3: message received:", string(msg.Data()))

// 		return nil
// 	})

// 	p2.Send(grav.NewMsg(grav.MsgTypeDefault, []byte("hello, world")))

// 	// messages are asyncronous, so pause for a second to allow the message to send
// 	<-time.After(time.Second * 4)

// 	p3.Send(grav.NewMsg(grav.MsgTypeDefault, []byte("hello, again")))

// 	<-time.After(time.Second * 2)
// }
