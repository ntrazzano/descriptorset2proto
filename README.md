# DescriptorSet To Proto

Converts a FileDescriptorSet (protocol buffer definition defined in a protocol buffer) back into the textual format.

These files can be generated with tooling such as `protoc`'s `--descriptor_set_out=` flag or `grpcurl`'s  `-protoset-out`
flag.

## Motivation
I wasn't able to find any existing tooling that would convert a protobuf descriptor file back into it's proto form. The
closest thing I could find was the protobuf C library's DebugString, but it's not exactly the same.

Also wanted an excuse to write more Kotlin.

## Syntax
### Supported
* [syntax](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#syntax)
* [import](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#import_statement)
* [package](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#package)
* [option](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#option)
  * file-level
  * message-level
  * field-level  
* [normal_field](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#normal_field)
* [oneof_and_oneof_field](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#oneof_and_oneof_field)
* [map_field](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#map_field)
* [reserved](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#reserved)
* [enum](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#enum_definition)
* [message](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#message_definitionn)
* [service](https://developers.google.com/protocol-buffers/docs/reference/proto3-spec#service_definition)
### Unsupported
* [custom_options](https://developers.google.com/protocol-buffers/docs/overview#customoptions)
## Install
This project uses kotlin and gradle.
```
./gradlew installDist
cd build/install/descriptorset2proto/bin
```

## Usage
```
usage: descriptorset2proto
 -c,--clean               clean destination folder before rebuild
 -d,--destination <arg>   destination folder to generate the protos
 -s,--source <arg>        source descriptor file, aka protoset
```

## Contributing

PRs accepted.

## License

MIT © Neil Razzano
