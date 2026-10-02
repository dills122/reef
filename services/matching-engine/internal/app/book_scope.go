package app

import (
	"strconv"
	"strings"
)

// Length framing avoids collisions when IDs contain separators or other keys.
func bookKey(runID, venueSessionID, instrumentID string) string {
	return framedID(runID) + framedID(venueSessionID) + framedID(instrumentID)
}

func framedID(id string) string { return strconv.Itoa(len(id)) + ":" + id }

func parseBookKey(key string) (BookScope, bool) {
	var values [3]string
	for i := range values {
		colon := strings.IndexByte(key, ':')
		if colon < 1 {
			return BookScope{}, false
		}
		length, err := strconv.Atoi(key[:colon])
		if err != nil || length < 0 || strconv.Itoa(length) != key[:colon] || length > len(key)-colon-1 {
			return BookScope{}, false
		}
		key = key[colon+1:]
		values[i] = key[:length]
		key = key[length:]
	}
	return BookScope{RunID: values[0], VenueSessionID: values[1], InstrumentID: values[2]}, key == ""
}

func bookKeyMatchesInstrument(key, instrumentID string) bool {
	scope, ok := parseBookKey(key)
	return ok && scope.InstrumentID == instrumentID
}
