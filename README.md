
# fix-translator

## What is it?
fix-translator is a library to help you translate collections into [FIX](http://www.fixprotocol.org/what-is-fix.shtml) messages and vice versa.

Basically, it helps you transform this:
```
"8=FIX.4.2\u00019=219\u000135=8\u000152=20130402-11:57:23\u000149=VENUE_ID\u0001
56=MY_ID\u000134=3\u000157=MY_SUB_ID\u000121=1\u000155=NESNz\u000159=0\u0001
54=1\u000160=20130402-11:57:23\u000138=100\u000140=2\u000144=1.000\u0001
37=13\u000111=1177\u000117=001A13\u000120=0\u0001150=0\u000139=0\u0001
151=100\u000114=0\u00016=0.0000\u000131=0.0000\u000132=0\u000110=220\u0001"
```

into this:
```clojure
{:symbol "NESNz", :cumulative-qty 0, :order-id "13", :target-comp-id "MY_ID",
:order-qty 100, :avg-price 0.0, :client-order-id "1177", :order-type :limit,
:sender-comp-id "VENUE_ID", :side :buy, :last-share 0, :order-status :new,
:msg-type :execution-report, :transact-time "20130402-11:57:24",
:leaves-qty 100, :last-price 0.0, :price 1.0}
```

and back again with any FIX specification.

In addition to performing two-way translation, when translating from collections to FIX, fix-translator also takes care of attaching message length and checksum information.

## Why does it exist?
fix-translator was written to provide translation services for [clj-fix](https://github.com/nitinpunjabi/clj-fix), but it can be used as a stand-alone library. It is released here under the MIT license.

## Related repos
- [clj-fix](https://github.com/nitinpunjabi/clj-fix)
- [clj-fix-oms](https://github.com/nitinpunjabi/clj-fix-oms)

## Installing (Leiningen)
```Clojure

;Include this in your project.clj:
[fix-translator "1.05"]

; Example:
(defproject my-project "1.0"
  :dependencies [[fix-translator "1.05"]])
```

## Usage
```Clojure

; In your ns statement:
(ns my-project.core
  (:use fix-translator.core))
```

## How fix-translator works
Each destination you communicate with, such as a broker or exchange, has its own FIX specification. To translate FIX messages for a particular destination, fix-translator requires a .spec file for that destination. This file basically contains a JSON object describing three things:

1. The name of the spec.This should correspond to the file name itself.
2. The spec description which is a mapping between the destination's FIX tags and values, and your preferred representations for those tags and values.
3. The Tags of Interest when translating from FIX messages to your representation (ie. which tags in the message you want translated for each message type). For example, for logon message types, you may be interested only in the sender id, and so on.

Take a look at this [sample spec](https://github.com/nitinpunjabi/fix-translator/blob/master/specs/test-market.spec) file to get an idea of what they look like.

## Creating a .spec file.
To use fix-translator, you need to create a .spec file based on your destination's FIX specification. For this tutorial, I'm going to use the FIX specification from [BATS Europe](http://cdn.batstrading.com/resources/participant_resources/BATS_Europe_FIX_Specification.pdf).

Create a file called bats-europe.spec. Save it in a folder called _specs_ in your project's root directory.

In the file, start the JSON with the spec's name:

```Json
{
  "name" : "bats-europe",

```

Then begin describing the spec. This basically entails going through your destination's FIX specification, and for each tag, specifying three things: the keyword you wish to assign to the tag, the transformation function, and if the tag can have a fixed set of values, a mapping between those values and the keywords you wish to refer to them with.

```
Here's what the structure looks like:
{
  "<your-name-for-the-tag>" : {
    "tag" : <tag value from specification>,
    "transform-by" : "<transform function>",
   }
}
```

"transform-by" can take one of four values. The first three: "to-int", "to-double" "to-string", simply take the tag's value and transforms it into an int, double, or string respectively. The fourth possible value, "by-value", takes the tag's value and maps it to a keyword for that value. This mapping is stored in a map called _values_.

An example will make things clearer. Scroll to page 15 of the [BATS specification](http://cdn.batstrading.com/resources/participant_resources/BATS_Europe_FIX_Specification.pdf). The first tag we see is 8 for _BeginString_. This can accept only one value: "FIX.4.2". So we extend our JSON object as follows:
```Json
{
  "name" : "bats-europe",
  "spec" : {
    "begin-string" : { 
      "tag" : "8",
      "transform-by" : "by-value",
      "values" : {
        "version" : "FIX.4.2"
      }
    },
```

The next tag is 9 for _BodyLength_ which is an integer.
```Json
{
  "name" : "bats-europe",
  "spec" : {
    "begin-string" : { 
      "tag" : "8",
      "transform-by" : "by-value",
      "values" : {
        "version" : "FIX.4.2"
      }
    },
    "body-length" : {
      "tag" : "8",
      "transform-by" : "to-int"
    },
```

And so on.

Finally, after the spec is complete, include which tags you want translated from FIX to your representation for each message type (tag 35 in BATS). For example, when receiving a Logon acknowledgement message (tag 35 = 'A'), you may want only the _SenderCompID_ (tag 49). In this case, you would include it in the JSON object as follows:

```Json
{
  "name" : "bats-europe",
  "spec" : {
    "begin-string" : { 
      "tag" : "8",
      "transform-by" : "by-value",
      "values" : {
        "version" : "FIX.4.2"
      }
    },
    "body-length" : {
      "tag" : "8",
      "transform-by" : "to-int"
    },
    .
    .
    .
    "tags-of-interest" : {
      "logon" : "49",
     .
     .
    .
   }
}
```

The label you give to each message type should correspond to the mapping you give in the spec.

## Using fix-translator
__1__. [Install]() and [include]() fix-translator in your project.

__2__. In your project's root directory, create a directory called specs. This is where you'll place your destination's .spec file.

__3__. [Create a spec file]() for your destination.

__4__. Load the spec for the destination
```Clojure
(load-spec :bats-europe)
```

__5__. To translate a message from a Clojure map representation into FIX:
```Clojure
; The params are [venue [:tag01 :value01 :tag02 :value02...]]
(encode-msg :bats-europe [:order-type :limit :time-in-force :ioc])
```

__6__. To translate a message from FIX into a Clojure map representation:
```Clojure
; The params are [fix-msg]
(decode-msg :bats-europe (get-msg-type fix-msg) fix-msg)
```

## To-Dos
- Reduce params for decode-msg to venue and fix-msg.
- Investigate reducers and other potential ways to speed up translation.
- Enable fix-translator encoding using string keys.
