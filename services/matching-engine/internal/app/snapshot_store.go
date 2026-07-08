package app

import (
	"compress/gzip"
	"encoding/json"
	"os"
	"path/filepath"
)

func WriteSnapshotFile(path string, snapshot Snapshot) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()

	gzipWriter := gzip.NewWriter(file)
	defer gzipWriter.Close()

	encoder := json.NewEncoder(gzipWriter)
	encoder.SetIndent("", "  ")
	return encoder.Encode(snapshot)
}

func ReadSnapshotFile(path string) (Snapshot, error) {
	file, err := os.Open(path)
	if err != nil {
		return Snapshot{}, err
	}
	defer file.Close()

	gzipReader, err := gzip.NewReader(file)
	if err != nil {
		return Snapshot{}, err
	}
	defer gzipReader.Close()

	var snapshot Snapshot
	if err := json.NewDecoder(gzipReader).Decode(&snapshot); err != nil {
		return Snapshot{}, err
	}
	return snapshot, nil
}
