//usr/bin/env go run "$0" "$@"; exit "$?"
package main

import (
	"bufio"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"github.com/ostelco/ostelco-core/sim-administration/sim-batch-management/es2plus"
	"github.com/ostelco/ostelco-core/sim-administration/sim-batch-management/fieldsyntaxchecks"
	"github.com/ostelco/ostelco-core/sim-administration/sim-batch-management/model"
	"github.com/ostelco/ostelco-core/sim-administration/sim-batch-management/outfileparser"
	"github.com/ostelco/ostelco-core/sim-administration/sim-batch-management/store"
	"github.com/ostelco/ostelco-core/sim-administration/sim-batch-management/uploadtoprime"
	kingpin "gopkg.in/alecthomas/kingpin.v2"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

//  "gopkg.in/alecthomas/kingpin.v2"
var (
	// TODO: Global flags can be added to Kingpin, but also make it have an effect.
	// debug    = kingpin.Flag("debug", "enable debug mode").Default("false").Bool()

	// Declare a profile-vendor with an SM-DP+ that can be referred to from
	// batches.  Referential integrity required, so it won't be possible to
	// declare bathes with non-existing profile vendors.

	dpv             = kingpin.Command("declare-profile-vendor", "Declare a profile vendor with an SM-DP+ we can talk to")
	dpvName         = dpv.Flag("name", "Name of profile-vendor").Required().String()
	dpvCertFilePath = dpv.Flag("cert", "Certificate pem file.").Required().String()
	dpvKeyFilePath  = dpv.Flag("key", "Certificate key file.").Required().String()
	dpvHost         = dpv.Flag("host", "Host of ES2+ endpoint.").Required().String()
	dpvPort         = dpv.Flag("port", "Port of ES2+ endpoint").Required().Int()
	dpvRequesterId  = es2.Flag("requester-id", "ES2+ requester ID.").Required().String()


	// TODO: Some command to list all profile-vendors, hsses, etc. , e.g. lspv, lshss, ...
	// TODO: Add sftp coordinates to be used when fetching/uploding input/utput-files
	// TODO: Declare hss-es, that can be refered to in profiles.
	// TODO: Declare legal hss/dpv combinations, batches must use legal combos.
	// TODO: Declare contact methods for primes.  It might be a good idea to
	//        impose referential integrity constraint on this too, so that
	//        profile/vendor/hss/prime combos are constrained.  It should be possible
	//        to specify prod/dev primes.

	es2    = kingpin.Command("es2", "Do things with the ES2+ protocol")
	es2cmd = es2.Arg("cmd",
		"The ES2+ subcommand, one of get-status, recover-profile, download-order, confirm-order, cancel-profile, bulk-activate-iccids, activate-Iccid, get-profile-activation-statuses-for-batch, get-profile-activation-statuses-for-iccids-in-file").Required().String()
	es2iccid        = es2.Arg("Iccid", "Iccid of profile to manipulate").String()
	es2Target       = es2.Arg("target-state", "Target state of recover-profile or cancel-profile command").Default("AVAILABLE").String()
	es2CertFilePath = es2.Flag("cert", "Certificate pem file.").Required().String()
	es2KeyFilePath  = es2.Flag("key", "Certificate key file.").Required().String()
	es2Hostport     = es2.Flag("hostport", "host:port of ES2+ endpoint.").Required().String()
	es2RequesterId  = es2.Flag("requesterid", "ES2+ requester ID.").Required().String()

	//
	// Convert an output (.out) file from an sim profile producer into an input file
	// for Prime.
	//

	/**
	 * OLD COMMENTS: Not yet reworked into doc for this script, but mostly accurate
	 *  nonetheless.
	 *
	 * This program is intended to be used from the command line, and will convert an
	 * output file from a sim card vendor into an input file for a HSS. The assumptions
	 * necessary for this to work are:
	 *
	 *  * The SIM card vendor produces output files similar to the example .out file
	 *     found in the same source directory as this program
	 *
	 *  * The HSS accepts input as a CSV file, with header line 'ICCID, IMSI, KI' and subsequent
	 *    lines containing ICCID/IMSI/Ki fields, all separated by commas.
	 *
	 * Needless to say, the outmost care should be taken when handling Ki values and
	 * this program must, as a matter of course, be considered a security risk, as
	 * must all  software that touch SIM values.
	 *
	 * With that caveat in place, the usage of this program typically looks like
	 * this:
	 *
	 *    ./outfile_to_hss_input_converter.go  \
	 *              -input-file sample_out_file_for_testing.out
	 *              -output-file-prefix  ./hss-input-for-
	 *
	 * (followed by cryptographically strong erasure of the .out file,
	 *  encapsulation of the .csv file in strong cryptography etc., none
	 *  of which are handled by this script).
	 */

	spUpload                 = kingpin.Command("sim-profile-upload", "Convert an output (.out) file from an sim profile producer into an input file for an HSS.")
	spUploadInputFile        = spUpload.Flag("input-file", "path to .out file used as input file").Required().String()
	spUploadOutputFilePrefix = spUpload.Flag("output-file-prefix",
		"prefix to path to .csv file used as input file, filename will be autogenerated").Required().String()

	// TODO: Check if this can be used for the key files.
	// postImage   = post.Flag("image", "image to post").ExistingFile()

	// TODO: listBatches = kingpin.Command("list-batches", "List all known batches.")

	describeBatch      = kingpin.Command("describe-batch", "Describe a batch with a particular name.")
	describeBatchBatch = describeBatch.Arg("batch", "The batch to describe").String()

	generateInputFile          = kingpin.Command("generate-input-file", "Generate input file for a named batch using stored parameters")
	generateInputFileBatchname = generateInputFile.Arg("batchname", "The batch to generate the input file for.").String()

	addMsisdnFromFile        = kingpin.Command("add-msisdn-from-file", "Add MSISDN from CSV file containing at least ICCID/MSISDN, but also possibly IMSI.")
	addMsisdnFromFileBatch   = addMsisdnFromFile.Flag("batch", "The batch to augment").Required().String()
	addMsisdnFromFileCsvfile = addMsisdnFromFile.Flag("csv-file", "The CSV file to read from").Required().ExistingFile()
	addMsisdnFromFileAddLuhn = addMsisdnFromFile.Flag("add-luhn-checksums", "Assume that the checksums for the ICCIDs are not present, and add them").Default("false").Bool()

	generateUploadBatch      = kingpin.Command("generate-batch-upload-script", "Generate a batch upload script")
	generateUploadBatchBatch = generateUploadBatch.Arg("batch", "The batch to generate upload script from").String()

	generateActivationCodeSql      = kingpin.Command("generate-activation-code-updating-sql", "Generate SQL code to update access codes")
	generateActivationCodeSqlBatch = generateActivationCodeSql.Arg("batch", "The batch to generate sql coce for").String()

	db           = kingpin.Command("declare-batch", "Declare a batch to be persisted, and used by other commands")
	dbName       = db.Flag("name", "Unique name of this batch").Required().String()
	dbAddLuhn    = db.Flag("add-luhn-checksums", "Assume that the checksums for the ICCIDs are not present, and add them").Default("false").Bool()
	dbCustomer   = db.Flag("customer", "Name of the customer of this batch (with respect to the sim profile vendor)").Required().String()
	dbBatchNo    = db.Flag("batch-no", "Unique number of this batch (with respect to the profile vendor)").Required().String()
	dbOrderDate  = db.Flag("order-date", "Order date in format ddmmyyyy").Required().String()
	dbFirstIccid = db.Flag("first-rawIccid",
		"An 18 or 19 digit long string.  The 19-th digit being a luhn luhnChecksum digit, if present").Required().String()
	dbLastIccid = db.Flag("last-rawIccid",
		"An 18 or 19 digit long string.  The 19-th digit being a luhn luhnChecksum digit, if present").Required().String()
	dbFirstIMSI         = db.Flag("first-imsi", "First IMSI in batch").Required().String()
	dbLastIMSI          = db.Flag("last-imsi", "Last IMSI in batch").Required().String()
	dbFirstMsisdn       = db.Flag("first-msisdn", "First MSISDN in batch").Required().String()
	dbLastMsisdn        = db.Flag("last-msisdn", "Last MSISDN in batch").Required().String()
	dbProfileType       = db.Flag("profile-type", "SIM profile type").Required().String()
	dbBatchLengthString = db.Flag(
		"batch-quantity",
		"Number of sim cards in batch").Required().String()

	dbHssVendor        = db.Flag("hss-vendor", "The HSS vendor").Default("M1").String()
	dbUploadHostname   = db.Flag("upload-hostname", "host to upload batch to").Default("localhost").String()
	dbUploadPortnumber = db.Flag("upload-portnumber", "port to upload to").Default("8080").String()

	dbProfileVendor = db.Flag("profile-vendor", "Vendor of SIM profiles").Default("Idemia").String()

	dbInitialHlrActivationStatusOfProfiles = db.Flag(
		"initial-hlr-activation-status-of-profiles",
		"Initial hss activation state.  Legal values are ACTIVATED and NOT_ACTIVATED.").Default("ACTIVATED").String()
)

func main() {
	if err := parseCommandLine(); err != nil {
		panic(err)
	}
}

func parseCommandLine() error {

	db, err := store.OpenFileSqliteDatabase("foobar.db")
	if err != nil {
		return fmt.Errorf("couldn't open sqlite database.  '%s'", err)
	}

	db.GenerateTables()

	cmd := kingpin.Parse()
	switch cmd {

	case "declare-profile-vendor":

		vendor, err := db.GetProfileVendorByName(*dpvName)
		if err != nil {
			return err
		}

		if vendor != nil {
			return fmt.Errorf("already declared profile vendor '%s'", *dpvName)
		}

		if _, err := os.Stat(*dpvCertFilePath); os.IsNotExist(err) {
			return fmt.Errorf("can't find certificate file '%s'", *dpvCertFilePath)
		}

		if _, err := os.Stat(*dpvKeyFilePath); os.IsNotExist(err) {
			return fmt.Errorf("can't find key file '%s'", *dpvKeyFilePath)
		}

		if *dpvPort <= 0 {
			return fmt.Errorf("port  must be positive was '%d'", *dpvPort)
		}

		if 65534 < *dpvPort {
			return fmt.Errorf("port must be smaller than or equal to 65535, was '%d'", *dpvPort)
		}

		// Modify the paths to absolute  paths.

		absDpvCertFilePath, err := filepath.Abs(*dpvCertFilePath)
		if err != nil {
			return err
		}
		absDpvKeyFilePath, err  := filepath.Abs(*dpvKeyFilePath)
		if err != nil {
			return err
		}
		v := &model.ProfileVendor{
			Name:        *dpvName,
			Es2PlusCert: absDpvCertFilePath,
			Es2PlusKey:  absDpvKeyFilePath ,
			Es2PlusHost: *dpvHost,
			Es2PlusPort: *dpvPort,
			Es2PlusRequesterId: *dpvRequesterId,
		}

		fmt.Printf("Profilevendor = %v", v)
		os.Exit(1)
		if err := db.CreateProfileVendor(v); err != nil {
			return err
		}

	case "sim-profile-upload":

		inputFile := *spUploadInputFile
		outputFilePrefix := *spUploadOutputFilePrefix

		outRecord := outfileparser.ParseOutputFile(inputFile)
		outputFile := outputFilePrefix + outRecord.OutputFileName + ".csv"
		log.Println("outputFile = ", outputFile)

		if err := outfileparser.WriteHssCsvFile(outputFile, outRecord.Entries); err != nil {
			return fmt.Errorf("couldn't close output file '%s', .  Error = '%v'", outputFilePrefix, err)
		}

	case "list-batches":

		allBatches, err := db.GetAllBatches()
		if err != nil {
			return err
		}

		fmt.Println("Names of current batches: ")
		for _, batch := range allBatches {
			fmt.Printf("  %s\n", batch.Name)
		}

	case "describe-batch":

		batch, err := db.GetBatchByName(*describeBatchBatch)
		if err != nil {
			return err
		}

		if batch == nil {
			return fmt.Errorf("no batch found with name '%s'", *describeBatchBatch)
		} else {
			bytes, err := json.MarshalIndent(batch, "    ", "     ")
			if err != nil {
				return fmt.Errorf("can't serialize batch '%v'", batch)
			}

			fmt.Printf("%v\n", string(bytes))
		}

	case "generate-activation-code-updating-sql":
		batch, err := db.GetBatchByName(*generateActivationCodeSqlBatch)
		if err != nil {
			return fmt.Errorf("couldn't find batch named '%s' (%s) ", *generateActivationCodeSqlBatch, err)
		}

		simEntries, err := db.GetAllSimEntriesForBatch(batch.BatchId)
		if err != nil {
			return err
		}

		for _, b := range simEntries {
			fmt.Printf(
				"UPDATE sim_entries SET matchingid = '%s', smdpplusstate = 'RELEASED', provisionstate = 'AVAILABLE' WHERE Iccid = '%s' and smdpplusstate = 'AVAILABLE';\n",
				b.ActivationCode,
				b.Iccid)
		}

	case "generate-batch-upload-script":
		batch, err := db.GetBatchByName(*generateUploadBatchBatch)
		if err != nil {
			return err
		}

		if batch == nil {
			return fmt.Errorf("no batch found with name '%s'", *describeBatchBatch)
		} else {
			var csvPayload = uploadtoprime.GenerateCsvPayload3(db, *batch)
			uploadtoprime.GeneratePostingCurlscript(batch.Url, csvPayload)
		}

	case "generate-input-file":
		batch, err := db.GetBatchByName(*generateInputFileBatchname)
		if err != nil {
			return err
		}

		if batch == nil {
			return fmt.Errorf("no batch found with name '%s'", *generateInputFileBatchname)
		} else {
			var result = GenerateInputFile(batch)
			fmt.Println(result)
		}

	case "add-msisdn-from-file":
		batchName := *addMsisdnFromFileBatch
		csvFilename := *addMsisdnFromFileCsvfile
		addLuhns := *addMsisdnFromFileAddLuhn

		batch, err := db.GetBatchByName(batchName)
		if err != nil {
			return err
		}

		csvFile, _ := os.Open(csvFilename)
		reader := csv.NewReader(bufio.NewReader(csvFile))

		defer csvFile.Close()

		headerLine, err := reader.Read()
		if err == io.EOF {
			break
		} else if err != nil {
			return err
		}

		var columnMap map[string]int
		columnMap = make(map[string]int)

		for index, fieldname := range headerLine {
			columnMap[strings.ToLower(fieldname)] = index
		}

		if _, hasIccid := columnMap["Iccid"]; !hasIccid {
			return fmt.Errorf("no ICCID  column in CSV file")
		}

		if _, hasMsisdn := columnMap["msisdn"]; !hasMsisdn {
			return fmt.Errorf("no MSISDN  column in CSV file")
		}

		if _, hasImsi := columnMap["imsi"]; !hasImsi {
			return fmt.Errorf("no IMSI  column in CSV file")
		}

		type csvRecord struct {
			iccid  string
			imsi   string
			msisdn string
		}

		var recordMap map[string]csvRecord
		recordMap = make(map[string]csvRecord)

		// Read all the lines into the record map.
		for {
			line, error := reader.Read()
			if error == io.EOF {
				break
			} else if error != nil {
				log.Fatal(error)
			}

			iccid := line[columnMap["Iccid"]]

			if addLuhns {
				iccid = fieldsyntaxchecks.AddLuhnChecksum(iccid)
			}

			record := csvRecord{
				iccid:  iccid,
				imsi:   line[columnMap["imsi"]],
				msisdn: line[columnMap["msisdn"]],
			}

			if _, duplicateRecordExists := recordMap[record.iccid]; duplicateRecordExists {
				return fmt.Errorf("duplicate ICCID record in map: %s", record.iccid)
			}

			recordMap[record.iccid] = record
		}

		simEntries, err := db.GetAllSimEntriesForBatch(batch.BatchId)
		if err != nil {
			return err
		}

		// Check for compatibility
		tx := db.Begin()
		noOfRecordsUpdated := 0
		for _, entry := range simEntries {
			record, iccidRecordIsPresent := recordMap[entry.Iccid]
			if !iccidRecordIsPresent {
				tx.Rollback()
				return fmt.Errorf("ICCID not in batch: %s", entry.Iccid)
			}

			if entry.Imsi != record.imsi {
				tx.Rollback()
				return fmt.Errorf("IMSI mismatch for ICCID=%s.  Batch has %s, csv file has %s", entry.Iccid, entry.Imsi, record.iccid)
			}

			if entry.Msisdn != "" && record.msisdn != "" && record.msisdn != entry.Msisdn {
				tx.Rollback()
				return fmt.Errorf("MSISDN mismatch for ICCID=%s.  Batch has %s, csv file has %s", entry.Iccid, entry.Msisdn, record.msisdn)
			}

			if entry.Msisdn == "" && record.msisdn != "" {
				err = db.UpdateSimEntryMsisdn(entry.Id, record.msisdn)
				if err != nil {
					tx.Rollback()
					return err
				}
				noOfRecordsUpdated += 1
			}
		}
		tx.Commit()

		log.Printf("Updated %d of a total of %d records in batch '%s'\n", noOfRecordsUpdated, len(simEntries), batchName)

	case "declare-batch":
		log.Println("Declare batch")
		db.DeclareBatch(
			*dbName,
			*dbAddLuhn,
			*dbCustomer,
			*dbBatchNo,
			*dbOrderDate,
			*dbFirstIccid,
			*dbLastIccid,
			*dbFirstIMSI,
			*dbLastIMSI,
			*dbFirstMsisdn,
			*dbLastMsisdn,
			*dbProfileType,
			*dbBatchLengthString,
			*dbHssVendor,
			*dbUploadHostname,
			*dbUploadPortnumber,
			*dbProfileVendor,
			*dbInitialHlrActivationStatusOfProfiles)

	case "es2":

		// TODO: Vet all the parameters, they can  very easily be bogus.
		client := es2plus.Client(*es2CertFilePath, *es2KeyFilePath, *es2Hostport, *es2RequesterId)
		iccid := *es2iccid
		switch *es2cmd {

		case "get-status":

			result, err := client.GetStatus(iccid)
			if err != nil {
				return err
			}

			log.Printf("Iccid='%s', state='%s', acToken='%s'\n", iccid, (*result).State, (*result).ACToken)
		case "recover-profile":
			err := checkEs2TargetState(es2Target)
			if err != nil {
				return err
			}
			result, err := client.RecoverProfile(iccid, *es2Target)
			if err != nil {
				return err
			}
			log.Println("result -> ", result)
		case "download-order":
			result, err := client.DownloadOrder(iccid)
			if err != nil {
				return err
			}
			log.Println("result -> ", result)
		case "confirm-order":
			result, err := client.ConfirmOrder(iccid)
			if err != nil {
				return err
			}
			fmt.Println("result -> ", result)
		case "activate-Iccid":
			result, err := client.ActivateIccid(iccid)

			if err != nil {
				return err
			}
			fmt.Printf("%s, %s\n", iccid, result.ACToken)

		case "get-profile-activation-statuses-for-iccids-in-file":
			csvFilename := iccid

			csvFile, _ := os.Open(csvFilename)
			reader := csv.NewReader(bufio.NewReader(csvFile))

			defer csvFile.Close()

			headerLine, error := reader.Read()
			if error == io.EOF {
				break
			} else if error != nil {
				log.Fatal(error)
			}

			var columnMap map[string]int
			columnMap = make(map[string]int)

			for index, fieldname := range headerLine {
				columnMap[strings.TrimSpace(strings.ToLower(fieldname))] = index
			}

			if _, hasIccid := columnMap["iccid"]; !hasIccid {
				return fmt.Errorf("no ICCID  column in CSV file")
			}

			type csvRecord struct {
				Iccid string
			}

			var recordMap map[string]csvRecord
			recordMap = make(map[string]csvRecord)

			// Read all the lines into the record map.
			for {
				line, err := reader.Read()
				if err == io.EOF {
					break
				} else if err != nil {
					return err
				}

				iccid := line[columnMap["Iccid"]]
				iccid = strings.TrimSpace(iccid)

				record := csvRecord{
					Iccid: iccid,
				}

				if _, duplicateRecordExists := recordMap[record.Iccid]; duplicateRecordExists {
					return fmt.Errorf("duplicate ICCID record in map: %s", record.Iccid)
				}

				recordMap[record.Iccid] = record
			}

			// XXX Is this really necessary? I don't think so
			var mutex = &sync.Mutex{}

			var waitgroup sync.WaitGroup

			// Limit concurrency of the for-loop below
			// to 160 goroutines.  The reason is that if we get too
			// many we run out of file descriptors, and we don't seem to
			// get much speedup after hundred or so.

			concurrency := 160
			sem := make(chan bool, concurrency)
			fmt.Printf("%s, %s\n", "ICCID", "STATE")
			for _, entry := range recordMap {

				//
				// Only apply activation if not already noted in the
				// database.
				//

				sem <- true

				waitgroup.Add(1)
				go func(entry csvRecord) {

					defer func() { <-sem }()

					result, err := client.GetStatus(entry.Iccid)
					if err != nil {
						panic(err)
					}

					if result == nil {
						panic(fmt.Sprintf("Couldn't find any status for Iccid='%s'\n", entry.Iccid))
					}

					mutex.Lock()
					fmt.Printf("%s, %s\n", entry.Iccid, result.State)
					mutex.Unlock()
					waitgroup.Done()
				}(entry)
			}

			waitgroup.Wait()
			for i := 0; i < cap(sem); i++ {
				sem <- true
			}

		case "get-profile-activation-statuses-for-batch":
			batchName := iccid

			log.Printf("Getting statuses for all profiles in batch  named %s\n", batchName)

			batch, err := db.GetBatchByName(batchName)
			if err != nil {
				return fmt.Errorf("unknown batch '%s'", batchName)
			}

			entries, err := db.GetAllSimEntriesForBatch(batch.BatchId)
			if err != nil {
				return err
			}

			if len(entries) != batch.Quantity {
				return fmt.Errorf("batch quantity retrieved from database (%d) different from batch quantity (%d)", len(entries), batch.Quantity)
			}

			log.Printf("Found %d profiles\n", len(entries))

			// XXX Is this really necessary? I don't think so
			var mutex = &sync.Mutex{}

			var waitgroup sync.WaitGroup

			// Limit concurrency of the for-loop below
			// to 160 goroutines.  The reason is that if we get too
			// many we run out of file descriptors, and we don't seem to
			// get much speedup after hundred or so.

			concurrency := 160
			sem := make(chan bool, concurrency)
			for _, entry := range entries {

				//
				// Only apply activation if not already noted in the
				// database.
				//

				sem <- true

				waitgroup.Add(1)
				go func(entry model.SimEntry) {

					defer func() { <-sem }()

					result, err := client.GetStatus(entry.Iccid)
					if err != nil {
						panic(err)
					}

					if result == nil {
						log.Printf("ERROR: Couldn't find any status for Iccid='%s'\n", entry.Iccid)
					}

					mutex.Lock()
					fmt.Printf("%s, %s\n", entry.Iccid, result.State)
					mutex.Unlock()
					waitgroup.Done()
				}(entry)
			}

			waitgroup.Wait()
			for i := 0; i < cap(sem); i++ {
				sem <- true
			}

		case "set-batch-activation-codes":
			batchName := iccid

			fmt.Printf("Getting batch  named %s\n", batchName)

			batch, err := db.GetBatchByName(batchName)
			if err != nil {
				return fmt.Errorf("unknown batch '%s'", batchName)
			}

			entries, err := db.GetAllSimEntriesForBatch(batch.BatchId)
			if err != nil {
				return err
			}

			if len(entries) != batch.Quantity {
				return fmt.Errorf("batch quantity retrieved from database (%d) different from batch quantity (%d)", len(entries), batch.Quantity)
			}

			// XXX Is this really necessary? I don't think so
			var mutex = &sync.Mutex{}

			var waitgroup sync.WaitGroup

			// Limit concurrency of the for-loop below
			// to 160 goroutines.  The reason is that if we get too
			// many we run out of file descriptors, and we don't seem to
			// get much speedup after hundred or so.

			concurrency := 160
			sem := make(chan bool, concurrency)
			tx := db.Begin()
			for _, entry := range entries {

				//
				// Only apply activation if not already noted in the
				// database.

				if entry.ActivationCode == "" {

					sem <- true

					waitgroup.Add(1)
					go func(entry model.SimEntry) {

						defer func() { <-sem }()

						result, err := client.ActivateIccid(entry.Iccid)
						if err != nil {
							panic(err)
						}

						mutex.Lock()
						fmt.Printf("%s, %s\n", entry.Iccid, result.ACToken)
						db.UpdateActivationCode(entry.Id, result.ACToken)
						mutex.Unlock()
						waitgroup.Done()
					}(entry)
				}
			}

			waitgroup.Wait()
			for i := 0; i < cap(sem); i++ {
				sem <- true
			}
			tx.Commit()

		case "bulk-activate-iccids":

			file, err := os.Open(iccid)
			if err != nil {
				log.Fatal(err)
			}
			defer file.Close()

			scanner := bufio.NewScanner(file)
			var mutex = &sync.Mutex{}
			var waitgroup sync.WaitGroup
			for scanner.Scan() {
				iccid := scanner.Text()
				waitgroup.Add(1)
				go func(i string) {

					result, err := client.ActivateIccid(i)
					if err != nil {
						panic(err)
					}
					mutex.Lock()
					fmt.Printf("%s, %s\n", i, result.ACToken)
					mutex.Unlock()
					waitgroup.Done()
				}(iccid)
			}

			waitgroup.Wait()

			if err := scanner.Err(); err != nil {
				log.Fatal(err)
			}

		case "cancel-profile":
			err := checkEs2TargetState(es2Target)
			if err != nil {
				return err
			}
			_, err = client.CancelOrder(iccid, *es2Target)
			if err != nil {
				return err
			}
		default:
			return fmt.Errorf("unknown es2+ subcommand '%s', try --help", *es2cmd)
		}
	case "batch":
		fmt.Println("Doing the batch thing.")
		// storage.doTheBatchThing()
	default:
		return fmt.Errorf("unknown command: '%s'", cmd)
	}

	return nil
}

func checkEs2TargetState(target *string) error {
	if *target != "AVAILABLE" {
		return fmt.Errorf("target ES2+ state unexpected, legal value(s) is(are): 'AVAILABLE'")
	} else {
		return nil
	}
}

///
///    Input batch management
///

func GenerateInputFile(batch *model.Batch) string {
	result := "*HEADER DESCRIPTION\n" +
		"***************************************\n" +
		fmt.Sprintf("Customer        : %s\n", batch.Customer) +
		fmt.Sprintf("ProfileType     : %s\n", batch.ProfileType) +
		fmt.Sprintf("Order Date      : %s\n", batch.OrderDate) +
		fmt.Sprintf("Batch No        : %s\n", batch.BatchNo) +
		fmt.Sprintf("Quantity        : %d\n", batch.Quantity) +
		"***************************************\n" +
		"*INPUT VARIABLES\n" +
		"***************************************\n" +
		"var_In:\n" +
		fmt.Sprintf(" ICCID: %s\n", batch.FirstIccid) +
		fmt.Sprintf("IMSI: %s\n", batch.FirstImsi) +
		"***************************************\n" +
		"*OUTPUT VARIABLES\n" +
		"***************************************\n" +
		"var_Out: ICCID/IMSI/KI\n"
	return result
}
