package outfileparser

// TODO: Rename to oufileparser

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"github.com/ostelco/ostelco-core/sim-administration/sim-batch-management/loltelutils"
	"github.com/ostelco/ostelco-core/sim-administration/sim-batch-management/model"
	"log"
	"os"
	"regexp"
	"strconv"
	"strings"
)

const (
	INITIAL            = "initial"
	HEADER_DESCRIPTION = "header_description"
	INPUT_VARIABLES    = "input_variables"
	OUTPUT_VARIABLES   = "output_variables"
	UNKNOWN_HEADER     = "unknown"
)

///
///  Functions
///

func ParseOutputToHssConverterCommandLine() (string, string) {
	inputFile := flag.String("input-file",
		"not  a valid filename",
		"path to .out file used as input file")

	outputFile := flag.String("output-file-prefix",
		"not  a valid filename",
		"prefix to path to .csv file used as input file, filename will be autogenerated")

	//
	// Parse input according to spec above
	//
	flag.Parse()
	return *inputFile, *outputFile
}

func ParseLineIntoKeyValueMap(line string, theMap map[string]string) {
	var splitString = strings.Split(line, ":")
	if len(splitString) != 2 {
		log.Fatalf("Unparsable colon separated key/value pair: '%s'\n", line)
	}
	key := strings.TrimSpace(splitString[0])
	value := strings.TrimSpace(splitString[1])
	theMap[key] = value
}

type ParserState struct {
	currentState      string
	inputVariables    map[string]string
	headerDescription map[string]string
	entries           []model.SimEntry
	csvFieldMap       map[string]int
}

func ParseVarOutLine(varOutLine string, result *map[string]int) (error) {
	varOutSplit := strings.Split(varOutLine, ":")

	if len(varOutSplit) != 2 {
		return errors.New("syntax error in var_out line.  More than two colon separated fields.")
	}

	varOutToken := strings.TrimSpace(string(varOutSplit[0]))
	if strings.ToLower(varOutToken) != "var_out" {
		return errors.New(fmt.Sprintf("syntax error in var_out line.  Does not start with 'var_out', was '%s'", varOutToken))
	}

	slashedFields := strings.Split(varOutSplit[1], "/")
	for index, columnName := range slashedFields {
		(*result)[columnName] = index
	}
	return nil
}

func ParseOutputFile(filename string) model.OutputFileRecord {

	_, err := os.Stat(filename)

	if os.IsNotExist(err) {
		log.Fatalf("Couldn't find file '%s'\n", filename)
	}
	if err != nil {
		log.Fatalf("Couldn't stat file '%s'\n", filename)
	}

	file, err := os.Open(filename) // For read access.
	if err != nil {
		log.Fatal(err)
	}

	defer file.Close()

	state := ParserState{
		currentState:      INITIAL,
		inputVariables:    make(map[string]string),
		headerDescription: make(map[string]string),
		csvFieldMap:       make(map[string]int),
	}

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {

		// Read line, trim spaces in both ends.
		line := scanner.Text()
		line = strings.TrimSpace(line)

		// Is this a line we should read quickly then
		// move on to the next...?
		if isComment(line) {
			continue
		} else if isSectionHeader(line) {
			nextMode := modeFromSectionHeader(line)
			transitionMode(&state, nextMode)
			continue
		}

		// ... or should we look closer at it and parse it
		// looking for real content?

		switch state.currentState {
		case HEADER_DESCRIPTION:
			ParseLineIntoKeyValueMap(line, state.headerDescription)
		case INPUT_VARIABLES:
			if line == "var_In:" {
				continue
			}
			ParseLineIntoKeyValueMap(line, state.inputVariables)
		case OUTPUT_VARIABLES:

			line = strings.TrimSpace(line)
			lowercaseLine := strings.ToLower(line)

			if (strings.HasPrefix(lowercaseLine, "var_out:")) {
				if (len(state.csvFieldMap) != 0) {
					log.Fatal("Parsing multiple 'var_out' lines can't be right")
				}
				err :=  ParseVarOutLine(line, &(state.csvFieldMap))
				if err != nil {
					log.Fatalf("Couldn't parse output variable declaration '%s'\n", err)
				}
				continue
			}

			if (len(state.csvFieldMap) == 0) {
				fmt.Println("Line = ", line)
				log.Fatal("Cannot parse CSV part of input file without having first parsed a CSV header.")
			}

			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}

			rawIccid, imsi, ki := parseOutputLine(state, line)

			iccidWithChecksum := rawIccid
			if strings.HasSuffix(rawIccid, "F") {
				iccidWithChecksum = loltelutils.TrimSuffix(rawIccid, 1)
			}

			var iccidWithoutChecksum = loltelutils.TrimSuffix(iccidWithChecksum, 1)
			// TODO: Enable this!! checkICCIDSyntax(iccidWithChecksum)
			entry := model.SimEntry{
				RawIccid:             rawIccid,
				IccidWithChecksum:    iccidWithChecksum,
				IccidWithoutChecksum: iccidWithoutChecksum,
				Imsi:                 imsi,
				Ki:                   ki}
			state.entries = append(state.entries, entry)

		case UNKNOWN_HEADER:
			continue

		default:
			log.Fatalf("Unknown parser state '%s'\n", state.currentState)
		}
	}

	if err := scanner.Err(); err != nil {
		log.Fatal(err)
	}

	countedNoOfEntries := len(state.entries)
	declaredNoOfEntities, err := strconv.Atoi(state.headerDescription["Quantity"])

	if err != nil {
		log.Fatal("Could not find 'Quantity' field while parsing file '", filename, "'")
	}

	if countedNoOfEntries != declaredNoOfEntities {
		log.Fatalf("Declared no of entities = %d, counted nunber of entities = %d. Mismatch!",
			declaredNoOfEntities,
			countedNoOfEntries)
	}

	result := model.OutputFileRecord{
		Filename:          filename,
		InputVariables:    state.inputVariables,
		HeaderDescription: state.headerDescription,
		Entries:           state.entries,
		NoOfEntries:       declaredNoOfEntities,
		OutputFileName:    getOutputFileName(state),
	}

	return result
}

func getOutputFileName(state ParserState) string {
	return "" + getCustomer(state) + "_" + getProfileType(state) + "_" + getBatchNo(state)
}

func getBatchNo(state ParserState) string {
	return state.headerDescription["Batch No"]
}

func getProfileType(state ParserState) string {
	return state.headerDescription["ProfileType"]
}

func getCustomer(state ParserState) string {
	// TODO: Maker safe, so that it fails reliably if Customer is not in map.
	//       also use constant, not magic string
	return state.headerDescription["Customer"]
}

func parseOutputLine(state ParserState, s string) (string, string, string) {
	parsedString := strings.Split(s, " ")
	return parsedString[state.csvFieldMap["ICCID"]], parsedString[state.csvFieldMap["IMSI"]], parsedString[state.csvFieldMap["KI"]]
}

func transitionMode(state *ParserState, targetState string) {
	state.currentState = targetState
}

// TODO: Consider replacing this thing with a map lookup.
func modeFromSectionHeader(s string) string {
	sectionName := s[1:]
	switch sectionName {
	case "HEADER DESCRIPTION":
		return HEADER_DESCRIPTION
	case "INPUT VARIABLES":
		return INPUT_VARIABLES
	case "OUTPUT VARIABLES":
		return OUTPUT_VARIABLES
	default:
		return UNKNOWN_HEADER
	}
}

func isSectionHeader(s string) bool {
	match, _ := regexp.MatchString("^\\*([A-Z0-9 ])+$", s)
	return match
}

func isComment(s string) bool {
	match, _ := regexp.MatchString("^\\*+$", s)
	return match
}

// fileExists checks if a file exists and is not a directory before we
// try using it to prevent further errors.
func fileExists(filename string) bool {
	info, err := os.Stat(filename)
	if os.IsNotExist(err) {
		return false
	}
	return !info.IsDir()
}

// TODO: Move this into some other package. "hssoutput" or something.
// TODO: Consider rewriting using https://golang.org/pkg/encoding/csv/
func WriteHssCsvFile(filename string, entries []model.SimEntry) error {

	if fileExists(filename) {
		log.Fatal("Output file already exists. '", filename, "'.")
	}

	f, err := os.Create(filename)
	if err != nil {
		log.Fatal("Couldn't create hss csv file '", filename, "': ", err)
	}

	_, err = f.WriteString("ICCID, IMSI, KI\n")
	if err != nil {
		log.Fatal("Couldn't header to  hss csv file '", filename, "': ", err)
	}

	max := 0
	for i, entry := range entries {
		s := fmt.Sprintf("%s, %s, %s\n", entry.IccidWithChecksum, entry.Imsi, entry.Ki)
		_, err = f.WriteString(s)
		if err != nil {
			log.Fatal("Couldn't write to  hss csv file '", filename, "': ", err)
		}
		max = i + 1
	}
	fmt.Println("Successfully written ", max, " sim card records.")
	return f.Close()
}

//
// Entrypoint
// TODO: Move this entrypoint into the the command processor
//

func ConvertInputfileToOutputfile(inputFile string, outputFilePrefix string) {
	outRecord := ParseOutputFile(inputFile)
	outputFile := outputFilePrefix + outRecord.OutputFileName + ".csv"
	fmt.Println("outputFile = ", outputFile)

	err := WriteHssCsvFile(outputFile, outRecord.Entries)
	if err != nil {
		log.Fatal("Couldn't close output file '", outputFilePrefix, "'.  Error = '", err, "'")
	}
}
