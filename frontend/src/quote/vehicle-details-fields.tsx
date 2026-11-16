import { useId } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import type { Catalog, VehicleDetails } from "./api";

export const differentVehicle = "enter-details";

export function readVehicleDetails(form: HTMLFormElement): VehicleDetails {
  const data = new FormData(form);
  return {
    make: String(data.get("vehicle-make") ?? ""),
    model: String(data.get("vehicle-model") ?? "").trim(),
    year: Number(data.get("vehicle-year")),
  };
}

export function VehicleDetailsFields({ catalog, suggested }: {
  catalog: Catalog;
  suggested?: Partial<VehicleDetails>;
}) {
  const id = useId();
  return (
    <div className="intake-field">
      <Label htmlFor={id + "-make"}>Make</Label>
      <NativeSelect id={id + "-make"} name="vehicle-make" defaultValue={suggested?.make ?? ""} required>
        <NativeSelectOption value="">Choose a make</NativeSelectOption>
        {catalog.makes.map((make) => <NativeSelectOption key={make} value={make}>{make}</NativeSelectOption>)}
      </NativeSelect>
      <Label htmlFor={id + "-model"}>Model</Label>
      <Input id={id + "-model"} name="vehicle-model" defaultValue={suggested?.model} required maxLength={48} />
      <Label htmlFor={id + "-year"}>Year</Label>
      <Input id={id + "-year"} name="vehicle-year" type="number" inputMode="numeric"
        defaultValue={suggested?.year} min={catalog.minVehicleYear} max={catalog.maxVehicleYear} required />
    </div>
  );
}
