ALTER TABLE nl_behov
    ADD COLUMN updated TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL;

-- Create a function to update the updated column
CREATE OR REPLACE FUNCTION update_updated_column()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Create the trigger
CREATE TRIGGER update_nl_behov_updated
    BEFORE UPDATE ON nl_behov
    FOR EACH ROW
EXECUTE FUNCTION update_updated_column();
