/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import java.net.InetSocketAddress
import org.scalatest.Inside
import akka.util.ByteString
import akka.event.NoLogging
import akka.stream.FlowMaterializer
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.scaladsl._
import akka.http.model.HttpEntity._
import akka.http.model.HttpMethods._
import akka.http.model._
import akka.http.model.headers._
import akka.http.util._

class HttpClientSpec extends AkkaSpec("akka.loggers = []\n akka.loglevel = OFF") with Inside {
  implicit val materializer = FlowMaterializer()

  "The client implementation" should {

    "properly handle a request/response round-trip" which {

      "has a request with empty entity" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com:80
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest(16)
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        responsesSub.request(1)
        responses.expectNext(HttpResponse())

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "has a request with default entity" in new TestSetup {
        val probe = StreamTestKit.PublisherProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com:80
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest(4)
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendNext(ByteString("XY"))
        expectWireData("XY")
        sub.sendComplete()

        netInSub.expectRequest(16)
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        responsesSub.request(1)
        responses.expectNext(HttpResponse())

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "has a response with a default entity" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com:80
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest(16)
        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        responsesSub.request(1)
        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = responses.expectNext()
        ct shouldEqual ContentTypes.`application/octet-stream`

        val probe = StreamTestKit.SubscriberProbe[ChunkStreamPart]()
        chunks.runWith(Sink(probe))
        val sub = probe.expectSubscription()

        sendWireData("3\nABC\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("ABC"))

        sendWireData("4\nDEFX\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("DEFX"))

        sendWireData("0\n\n")
        sub.request(1)
        probe.expectNext(HttpEntity.LastChunk)
        probe.expectComplete()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "exhibits eager request stream completion" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        requestsSub.sendComplete()
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com:80
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest(16)
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        responsesSub.request(1)
        responses.expectNext(HttpResponse())

        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }
    }

    "produce proper errors" which {

      "catch the entity stream being shorter than the Content-Length" in new TestSetup {
        val probe = StreamTestKit.PublisherProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com:80
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest(4)
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendComplete()

        val InvalidContentLengthException(info) = netOut.expectError()
        info.summary shouldEqual "HTTP message had declared Content-Length 8 but entity data stream amounts to 2 bytes less"
        netInSub.sendComplete()
        responses.expectComplete()
        netInSub.expectCancellation()
      }

      "catch the entity stream being longer than the Content-Length" in new TestSetup {
        val probe = StreamTestKit.PublisherProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com:80
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest(4)
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendNext(ByteString("XYZ"))

        val InvalidContentLengthException(info) = netOut.expectError()
        info.summary shouldEqual "HTTP message had declared Content-Length 8 but entity data stream amounts to more bytes"
        netInSub.sendComplete()
        responses.expectComplete()
        netInSub.expectCancellation()
      }

      "catch illegal response starts" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com:80
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest(16)
        sendWireData(
          """HTTP/1.2 200 OK
            |
            |""")

        val error @ IllegalResponseException(info) = responses.expectError()
        info.summary shouldEqual "The server-side HTTP version is not supported"
        netOut.expectError(error)
        requestsSub.expectCancellation()
      }

      "catch illegal response chunks" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com:80
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest(16)
        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        responsesSub.request(1)
        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = responses.expectNext()
        ct shouldEqual ContentTypes.`application/octet-stream`

        val probe = StreamTestKit.SubscriberProbe[ChunkStreamPart]()
        chunks.runWith(Sink(probe))
        val sub = probe.expectSubscription()

        sendWireData("3\nABC\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("ABC"))

        sendWireData("4\nDEFXX")
        sub.request(1)
        val error @ EntityStreamException(info) = probe.expectError()
        info.summary shouldEqual "Illegal chunk termination"

        responses.expectComplete()
        netOut.expectComplete()
        requestsSub.expectCancellation()
      }

      "catch a response start truncation" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com:80
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest(16)
        sendWireData("HTTP/1.1 200 OK")
        netInSub.sendComplete()

        val error @ IllegalResponseException(info) = responses.expectError()
        info.summary shouldEqual "Illegal HTTP message start"
        netOut.expectError(error)
        requestsSub.expectCancellation()
      }
    }
  }

  class TestSetup {
    val requests = StreamTestKit.PublisherProbe[HttpRequest]
    val responses = StreamTestKit.SubscriberProbe[HttpResponse]
    val remoteAddress = new InetSocketAddress("example.com", 80)

    def settings = ClientConnectionSettings(system)
      .copy(userAgentHeader = Some(`User-Agent`(List(ProductVersion("akka-http", "test")))))

    val (netOut, netIn) = {
      val netOut = StreamTestKit.SubscriberProbe[ByteString]
      val netIn = StreamTestKit.PublisherProbe[ByteString]
      val clientFlow = HttpClient.transportToConnectionClientFlow(
        Flow(Sink(netOut), Source(netIn)), remoteAddress, settings, NoLogging)
      Source(requests).via(clientFlow).runWith(Sink(responses))
      netOut -> netIn
    }

    def wipeDate(string: String) =
      string.fastSplit('\n').map {
        case s if s.startsWith("Date:") ⇒ "Date: XXXX\r"
        case s                          ⇒ s
      }.mkString("\n")

    val netInSub = netIn.expectSubscription()
    val netOutSub = netOut.expectSubscription()
    val requestsSub = requests.expectSubscription()
    val responsesSub = responses.expectSubscription()

    def sendWireData(data: String): Unit = sendWireData(ByteString(data.stripMarginWithNewline("\r\n"), "ASCII"))
    def sendWireData(data: ByteString): Unit = netInSub.sendNext(data)

    def expectWireData(s: String) = {
      netOutSub.request(1)
      netOut.expectNext().utf8String shouldEqual s.stripMarginWithNewline("\r\n")
    }

    def closeNetworkInput(): Unit = netInSub.sendComplete()
  }
}