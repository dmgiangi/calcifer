FROM alpine:3.19

RUN apk add --no-cache curl jq bash

COPY configure.sh /configure.sh
RUN chmod +x /configure.sh

ENTRYPOINT ["/configure.sh"]

