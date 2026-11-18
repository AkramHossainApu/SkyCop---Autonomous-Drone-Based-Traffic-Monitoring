import { Canvas, useFrame, useThree } from '@react-three/fiber/native';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Pressable, StatusBar, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import { Color, Fog, Group, InstancedMesh, MathUtils, MeshStandardMaterial, Object3D, PerspectiveCamera, Vector3 } from 'three';

type Vec3 = { x: number; y: number; z: number };
type Vehicle = { x: number; z: number; yaw: number; speed: number };
type FlightState = {
  position: Vec3;
  previous: Vec3;
  velocity: number;
  yaw: number;
  viewYaw: number;
  viewPitch: number;
  time: number;
  collision: boolean;
  chunks: string[];
  vehicles: Vehicle[];
};
type Controls = { yaw: number; throttle: number; lookX: number; lookY: number; climb: number };

type Tile = { x: number; z: number; key: string; slot: number };
export type FlightControls = Controls;
const TILE = 56;
const ROAD = 11;
const palette = ['#d3d8c8', '#c4d0c3', '#d7cdbb', '#aebfb4', '#dcd7c8'];
const carPalette = ['#e87654', '#375d51', '#e3b94f', '#e8e9dc', '#bd5d48', '#658477', '#547293'];
const emptyVehicles: Vehicle[] = Array.from({ length: 40 }, () => ({ x: 0, z: 0, yaw: 0, speed: 0 }));

function keyFor(x: number, z: number) { return `${x}:${z}`; }
function signalCycle(state: FlightState, tile: Tile) { return (state.time + Math.abs(tile.x * 3 + tile.z * 5) * 1.7) % 20; }

function tilesAhead(position: Vec3, yaw: number): Tile[] {
  const forwardX = Math.round(Math.sin(yaw));
  const forwardZ = -Math.round(Math.cos(yaw));
  const sideX = -forwardZ;
  const sideZ = forwardX;
  const baseX = Math.floor(position.x / TILE);
  const baseZ = Math.floor(position.z / TILE);
  const tiles: Tile[] = [];
  for (let forward = 0; forward <= 3; forward++) {
    for (let side = -2; side <= 1; side++) {
      const x = baseX + forwardX * forward + sideX * side;
      const z = baseZ + forwardZ * forward + sideZ * side;
      tiles.push({ x, z, key: keyFor(x, z), slot: tiles.length });
    }
  }
  return tiles;
}

function makeBuildings(tile: Tile) {
  const centerX = tile.x * TILE + TILE / 2;
  const centerZ = tile.z * TILE + TILE / 2;
  return [-1, 1].flatMap((sideX) => [-1, 1].map((sideZ) => {
    const seed = Math.abs((tile.x * 73856093) ^ (tile.z * 19349663) ^ (sideX * 83492791) ^ sideZ) >>> 0;
    return {
      x: centerX + sideX * 14,
      z: centerZ + sideZ * 14,
      width: 7 + seed % 4,
      depth: 7 + (seed >>> 4) % 4,
      height: 9 + (seed >>> 8) % 23,
      color: palette[seed % palette.length],
      seed,
    };
  }));
}

function Buildings({ tiles }: { tiles: Tile[] }) {
  const buildings = useMemo(() => tiles.flatMap(makeBuildings), [tiles]);
  const bodyRef = useRef<InstancedMesh>(null);
  const roofRef = useRef<InstancedMesh>(null);
  const rooftopRef = useRef<InstancedMesh>(null);
  const frontWindowsRef = useRef<InstancedMesh>(null);
  const sideWindowsRef = useRef<InstancedMesh>(null);
  const windowCount = buildings.length * 9;

  useLayoutEffect(() => {
    if (!bodyRef.current || !roofRef.current || !rooftopRef.current || !frontWindowsRef.current || !sideWindowsRef.current) return;
    const transform = new Object3D();
    buildings.forEach((building, index) => {
      transform.position.set(building.x, building.height / 2, building.z);
      transform.scale.set(building.width, building.height, building.depth);
      transform.updateMatrix();
      bodyRef.current!.setMatrixAt(index, transform.matrix);
      bodyRef.current!.setColorAt(index, new Color(building.color));

      transform.position.set(building.x, building.height + 0.16, building.z);
      transform.scale.set(building.width + 0.25, 0.3, building.depth + 0.25);
      transform.updateMatrix();
      roofRef.current!.setMatrixAt(index, transform.matrix);
      roofRef.current!.setColorAt(index, new Color(building.seed % 2 ? '#82948a' : '#a66f57'));

      transform.position.set(building.x, building.height + 0.35, building.z);
      transform.scale.set(building.width * 0.48, 0.1, building.depth * 0.48);
      transform.updateMatrix();
      rooftopRef.current!.setMatrixAt(index, transform.matrix);

      for (let row = 0; row < 3; row++) {
        for (let column = 0; column < 3; column++) {
          const windowIndex = index * 9 + row * 3 + column;
          const localX = -building.width * 0.31 + column * building.width * 0.31;
          const localZ = -building.depth * 0.31 + column * building.depth * 0.31;
          const y = 2.4 + row * 2.5;
          transform.position.set(building.x + localX, y, building.z + building.depth / 2 + 0.012);
          transform.scale.set(building.width * 0.16, 0.75, 0.025);
          transform.updateMatrix();
          frontWindowsRef.current!.setMatrixAt(windowIndex, transform.matrix);
          transform.position.set(building.x + building.width / 2 + 0.012, y, building.z + localZ);
          transform.scale.set(0.025, 0.75, building.depth * 0.16);
          transform.updateMatrix();
          sideWindowsRef.current!.setMatrixAt(windowIndex, transform.matrix);
        }
      }
    });
    for (const mesh of [bodyRef.current, roofRef.current, rooftopRef.current, frontWindowsRef.current, sideWindowsRef.current]) {
      mesh.instanceMatrix.needsUpdate = true;
    }
    if (bodyRef.current.instanceColor) bodyRef.current.instanceColor.needsUpdate = true;
    if (roofRef.current.instanceColor) roofRef.current.instanceColor.needsUpdate = true;
  }, [buildings]);

  return (
    <group>
      <instancedMesh ref={bodyRef} args={[undefined, undefined, buildings.length]} count={buildings.length}>
        <boxGeometry args={[1, 1, 1]} /><meshStandardMaterial roughness={0.88} />
      </instancedMesh>
      <instancedMesh ref={roofRef} args={[undefined, undefined, buildings.length]} count={buildings.length}>
        <boxGeometry args={[1, 1, 1]} /><meshStandardMaterial roughness={0.8} />
      </instancedMesh>
      <instancedMesh ref={rooftopRef} args={[undefined, undefined, buildings.length]} count={buildings.length}>
        <boxGeometry args={[1, 1, 1]} /><meshStandardMaterial color="#75847b" roughness={0.78} />
      </instancedMesh>
      <instancedMesh ref={frontWindowsRef} args={[undefined, undefined, windowCount]} count={windowCount}>
        <boxGeometry args={[1, 1, 1]} /><meshStandardMaterial color="#89a19c" emissive="#d5b474" emissiveIntensity={0.12} metalness={0.24} roughness={0.2} />
      </instancedMesh>
      <instancedMesh ref={sideWindowsRef} args={[undefined, undefined, windowCount]} count={windowCount}>
        <boxGeometry args={[1, 1, 1]} /><meshStandardMaterial color="#89a19c" emissive="#d5b474" emissiveIntensity={0.12} metalness={0.24} roughness={0.2} />
      </instancedMesh>
    </group>
  );
}

function TileWorld({ tile, state }: { tile: Tile; state: FlightState }) {
  const groundX = tile.x * TILE + TILE / 2;
  const groundZ = tile.z * TILE + TILE / 2;
  return (
    <group>
      <mesh position={[groundX, -0.12, groundZ]}>
        <boxGeometry args={[TILE, 0.2, TILE]} />
        <meshStandardMaterial color="#a8b89e" roughness={1} />
      </mesh>
      <mesh position={[groundX, 0.005, groundZ]}>
        <boxGeometry args={[TILE, 0.08, ROAD]} />
        <meshStandardMaterial color="#56645e" roughness={0.94} />
      </mesh>
      <mesh position={[groundX, 0.006, groundZ]} rotation={[0, Math.PI / 2, 0]}>
        <boxGeometry args={[TILE, 0.08, ROAD]} />
        <meshStandardMaterial color="#56645e" roughness={0.94} />
      </mesh>
      {Array.from({ length: 16 }, (_, index) => (
        <group key={index}>
          <mesh position={[groundX - 25 + index * 3.3, 0.06, groundZ]}><boxGeometry args={[1.3, 0.018, 0.12]} /><meshBasicMaterial color="#dfdfcd" /></mesh>
          <mesh position={[groundX, 0.06, groundZ - 25 + index * 3.3]}><boxGeometry args={[0.12, 0.018, 1.3]} /><meshBasicMaterial color="#dfdfcd" /></mesh>
        </group>
      ))}
      <Traffic state={state} tile={tile} />
    </group>
  );
}

function TrafficLights({ tiles, state }: { tiles: Tile[]; state: FlightState }) {
  const poleCount = tiles.length * 4;
  const headCount = tiles.length * 8;
  const bulbCount = tiles.length * 24;
  const poles = useRef<InstancedMesh>(null);
  const heads = useRef<InstancedMesh>(null);
  const bulbs = useRef<InstancedMesh>(null);
  const phaseKey = useRef('');

  useLayoutEffect(() => {
    if (!poles.current || !heads.current || !bulbs.current) return;
    const transform = new Object3D();
    tiles.forEach((tile, tileIndex) => {
      const centerX = tile.x * TILE + TILE / 2;
      const centerZ = tile.z * TILE + TILE / 2;
      const corners = [[-7, -7], [7, -7], [-7, 7], [7, 7]] as const;
      corners.forEach(([offsetX, offsetZ], cornerIndex) => {
        const poleX = centerX + offsetX;
        const poleZ = centerZ + offsetZ;
        const signalIndex = tileIndex * 4 + cornerIndex;
        transform.position.set(poleX, 2.5, poleZ);
        transform.scale.set(1, 5, 1);
        transform.updateMatrix();
        poles.current!.setMatrixAt(signalIndex, transform.matrix);
        for (let direction = 0; direction < 2; direction++) {
          const headIndex = signalIndex * 2 + direction;
          const headX = poleX + (direction === 0 ? 0.17 : -0.17);
          transform.position.set(headX, 4.65, poleZ);
          transform.rotation.set(0, direction === 0 ? 0 : Math.PI / 2, 0);
          transform.scale.set(0.46, 1.25, 0.35);
          transform.updateMatrix();
          heads.current!.setMatrixAt(headIndex, transform.matrix);
          transform.rotation.set(0, 0, 0);
          for (let phase = 0; phase < 3; phase++) {
            const bulbIndex = headIndex * 3 + phase;
            transform.position.set(headX, 5.02 - phase * 0.37, poleZ + 0.24);
            transform.scale.set(0.075, 0.075, 0.075);
            transform.updateMatrix();
            bulbs.current!.setMatrixAt(bulbIndex, transform.matrix);
          }
        }
      });
    });
    poles.current.instanceMatrix.needsUpdate = true;
    heads.current.instanceMatrix.needsUpdate = true;
    bulbs.current.instanceMatrix.needsUpdate = true;
  }, [tiles]);

  useFrame(() => {
    const nextPhaseKey = tiles.map((tile) => Math.floor(signalCycle(state, tile) / 2)).join(',');
    if (nextPhaseKey === phaseKey.current || !bulbs.current) return;
    phaseKey.current = nextPhaseKey;
    tiles.forEach((tile, tileIndex) => {
      const cycle = signalCycle(state, tile);
      for (let corner = 0; corner < 4; corner++) {
        for (let direction = 0; direction < 2; direction++) {
          const activePhase = direction === 0
            ? cycle < 8 ? 2 : cycle < 10 ? 1 : 0
            : cycle >= 10 && cycle < 18 ? 2 : cycle >= 18 ? 1 : 0;
          for (let phase = 0; phase < 3; phase++) {
            const color = phase === activePhase
              ? ['#ed584d', '#eabf56', '#68bb7e'][phase]
              : ['#471d1d', '#493d1d', '#1d4328'][phase];
            bulbs.current!.setColorAt(((tileIndex * 4 + corner) * 2 + direction) * 3 + phase, new Color(color));
          }
        }
      }
    });
    if (bulbs.current.instanceColor) bulbs.current.instanceColor.needsUpdate = true;
  });

  return (
    <group>
      <instancedMesh ref={poles} args={[undefined, undefined, poleCount]} count={poleCount}>
        <cylinderGeometry args={[0.09, 0.12, 1, 8]} /><meshStandardMaterial color="#424f4a" metalness={0.42} />
      </instancedMesh>
      <instancedMesh ref={heads} args={[undefined, undefined, headCount]} count={headCount}>
        <boxGeometry args={[1, 1, 1]} /><meshStandardMaterial color="#27312e" metalness={0.25} roughness={0.42} />
      </instancedMesh>
      <instancedMesh ref={bulbs} args={[undefined, undefined, bulbCount]} count={bulbCount}>
        <sphereGeometry args={[1, 10, 8]} /><meshBasicMaterial />
      </instancedMesh>
    </group>
  );
}

function Traffic({ state, tile }: { state: FlightState; tile: Tile }) {
  return <group>{Array.from({ length: 2 }, (_, index) => <Car key={index} state={state} tile={tile} index={index} />)}</group>;
}

function Car({ state, tile, index }: { state: FlightState; tile: Tile; index: number }) {
  const car = useRef<Group>(null);
  const progress = useRef(index * 17 + Math.abs(tile.x * 13 + tile.z * 5) % 31);
  const axis = (Math.abs(tile.x + tile.z + index) % 2) === 0 ? 'x' : 'z';
  const laneOffset = index === 0 ? -1.65 : 1.65;
  const speed = 5.8 + ((Math.abs(tile.x * 7 + tile.z * 3 + index) % 4) * 0.7);
  useFrame((_, delta) => {
    if (!car.current) return;
    const dt = Math.min(delta, 0.05);
    const progressWithinTile = ((progress.current % TILE) + TILE) % TILE;
    const distanceToLight = TILE - progressWithinTile;
    const phase = signalCycle(state, tile);
    const isGreen = axis === 'x' ? phase < 8 : phase >= 10 && phase < 18;
    let currentSpeed = !isGreen && distanceToLight < 7 ? Math.max(0, speed * (distanceToLight - 2) / 3) : speed;
    const loopBeforeMove = ((progress.current % (TILE * 3)) + TILE * 3) % (TILE * 3) - TILE;
    const baseX = tile.x * TILE + TILE / 2;
    const baseZ = tile.z * TILE + TILE / 2;
    const laneCoordinate = axis === 'x' ? baseZ + laneOffset : baseX + laneOffset;
    const distanceBeforeMove = ((loopBeforeMove % TILE) + TILE) % TILE;
    const currentCoordinate = axis === 'x' ? baseX + loopBeforeMove : baseZ + loopBeforeMove;
    const carYaw = axis === 'x' ? -Math.PI / 2 : Math.PI;
    for (const other of state.vehicles) {
      const sameDirection = Math.abs(Math.sin((other.yaw - carYaw) / 2)) < 0.12;
      const sameLane = axis === 'x' ? Math.abs(other.z - laneCoordinate) < 2.2 : Math.abs(other.x - laneCoordinate) < 2.2;
      if (other.speed <= 0 || !sameDirection || !sameLane) continue;
      const otherCoordinate = axis === 'x' ? other.x : other.z;
      const gap = ((otherCoordinate - currentCoordinate + TILE * 3) % (TILE * 3) + TILE * 3) % (TILE * 3);
      if (gap > 0.2 && gap < 5.2) currentSpeed = Math.min(currentSpeed, Math.max(0, speed * (gap - 2.2) / 3));
    }
    if (!isGreen && distanceBeforeMove > TILE - 7 && currentSpeed < speed * 0.15) currentSpeed = 0;
    progress.current += currentSpeed * dt;
    const loop = ((progress.current % (TILE * 3)) + TILE * 3) % (TILE * 3) - TILE;
    car.current.position.set(axis === 'x' ? baseX + loop : laneCoordinate, 0.46, axis === 'z' ? baseZ + loop : laneCoordinate);
    car.current.rotation.y = axis === 'x' ? -Math.PI / 2 : Math.PI;
    const vehicleIndex = tile.slot * 2 + index;
    state.vehicles[vehicleIndex] = { x: car.current.position.x, z: car.current.position.z, yaw: car.current.rotation.y, speed: currentSpeed };
  });
  const color = carPalette[Math.abs(tile.x * 19 + tile.z * 11 + index * 3) % carPalette.length];
  return (
    <group ref={car}>
      <mesh><boxGeometry args={[1.9, 0.75, 3.4]} /><meshStandardMaterial color={color} metalness={0.2} roughness={0.38} /></mesh>
      <mesh position={[0, 0.48, 0.12]}><boxGeometry args={[1.52, 0.52, 1.75]} /><meshStandardMaterial color="#91aaa6" roughness={0.3} metalness={0.18} transparent opacity={0.82} /></mesh>
      <mesh position={[0, 0.18, -1.58]}><boxGeometry args={[1.6, 0.12, 0.16]} /><meshStandardMaterial color="#d8d0b9" metalness={0.6} /></mesh>
      {([-1, 1] as const).flatMap((side) => [-1, 1].map((end) => (
        <group key={`${side}:${end}`} position={[side * 0.82, -0.15, end * 1.05]}>
          <mesh rotation={[0, 0, Math.PI / 2]}><cylinderGeometry args={[0.28, 0.28, 0.16, 16]} /><meshStandardMaterial color="#28302e" roughness={0.88} /></mesh>
          <mesh rotation={[0, 0, Math.PI / 2]}><cylinderGeometry args={[0.12, 0.12, 0.17, 12]} /><meshStandardMaterial color="#b9bcae" metalness={0.7} roughness={0.26} /></mesh>
        </group>
      )))}
      <mesh position={[-0.58, 0.02, -1.72]}><boxGeometry args={[0.28, 0.15, 0.04]} /><meshBasicMaterial color="#f6e5ad" /></mesh>
      <mesh position={[0.58, 0.02, -1.72]}><boxGeometry args={[0.28, 0.15, 0.04]} /><meshBasicMaterial color="#f6e5ad" /></mesh>
      <mesh position={[-0.58, 0.02, 1.72]}><boxGeometry args={[0.28, 0.15, 0.04]} /><meshBasicMaterial color="#e87654" /></mesh>
      <mesh position={[0.58, 0.02, 1.72]}><boxGeometry args={[0.28, 0.15, 0.04]} /><meshBasicMaterial color="#e87654" /></mesh>
    </group>
  );
}

function Drone({ state }: { state: FlightState }) {
  const drone = useRef<Group>(null);
  useFrame(() => {
    if (drone.current) {
      drone.current.position.set(state.position.x, state.position.y, state.position.z);
      drone.current.rotation.set(0, state.yaw, 0);
    }
  });
  return (
    <group ref={drone}>
      <mesh><boxGeometry args={[1.45, 0.38, 1.12]} /><meshStandardMaterial color="#d5ddd1" metalness={0.45} roughness={0.34} /></mesh>
      <mesh position={[0, 0.19, 0]}><sphereGeometry args={[0.31, 18, 12]} /><meshStandardMaterial color="#eef0e5" metalness={0.35} roughness={0.22} /></mesh>
      <mesh position={[0, 0.02, -0.65]} rotation={[-Math.PI / 2, 0, 0]}><coneGeometry args={[0.34, 0.4, 8]} /><meshStandardMaterial color="#e87654" metalness={0.4} roughness={0.3} /></mesh>
      <mesh position={[0, -0.22, -0.76]}><boxGeometry args={[0.48, 0.3, 0.34]} /><meshStandardMaterial color="#d66b4b" metalness={0.42} roughness={0.27} /></mesh>
      <mesh position={[0, -0.22, -0.96]}><torusGeometry args={[0.2, 0.045, 10, 24]} /><meshStandardMaterial color="#e5c27a" metalness={0.8} roughness={0.2} /></mesh>
      <mesh position={[0, -0.22, -0.98]}><sphereGeometry args={[0.15, 20, 14]} /><meshPhysicalMaterial color="#11617a" metalness={0.7} roughness={0.12} clearcoat={1} /></mesh>
      <mesh position={[0, 0.42, 0]}><boxGeometry args={[0.24, 0.05, 0.48]} /><meshStandardMaterial color="#c5a15e" metalness={0.6} roughness={0.3} /></mesh>
      {([-1, 1] as const).flatMap((side) => [-1, 1].map((forward) => (
        <group key={`${side}:${forward}`} position={[side * 0.96, 0.03, forward * 0.92]}>
          <mesh rotation={[0, 0, Math.PI / 2]} position={[-side * 0.4, 0, -forward * 0.37]}><cylinderGeometry args={[0.065, 0.07, 0.9, 8]} /><meshStandardMaterial color="#6e7c73" metalness={0.65} roughness={0.28} /></mesh>
          <mesh position={[0, 0.1, 0]}><cylinderGeometry args={[0.12, 0.15, 0.22, 12]} /><meshStandardMaterial color="#829086" metalness={0.72} roughness={0.25} /></mesh>
          <mesh><boxGeometry args={[0.88, 0.028, 0.095]} /><meshStandardMaterial color="#f0eee3" /></mesh>
          <mesh position={[0, 0.035, 0]}><boxGeometry args={[0.72, 0.018, 0.065]} /><meshStandardMaterial color="#e87654" transparent opacity={0.28} /></mesh>
        </group>
      )))}
      <mesh position={[0, -0.48, 0.35]} rotation={[0, 0, Math.PI / 2]}><cylinderGeometry args={[0.035, 0.04, 1.3, 8]} /><meshStandardMaterial color="#8d998e" metalness={0.6} /></mesh>
    </group>
  );
}

function FlightLogic({ state, controls, tiles, buildingColliders, setTiles }: {
  state: FlightState; controls: React.MutableRefObject<Controls>; tiles: Tile[]; buildingColliders: ReturnType<typeof makeBuildings>; setTiles: (tiles: Tile[]) => void;
}) {
  const previousChunk = useRef('');
  useFrame((_, delta) => {
    const dt = Math.min(delta, 0.04);
    const control = controls.current;
    state.time += dt;
    state.previous = { ...state.position };
    state.yaw -= control.yaw * dt * 1.35;
    state.viewYaw -= control.lookX * dt * 1.65;
    state.viewPitch = MathUtils.clamp(state.viewPitch + control.lookY * dt, -0.08, 0.86);
    const targetSpeed = -control.throttle * 19;
    state.velocity = MathUtils.damp(state.velocity, targetSpeed, 2.8, dt);
    const next = {
      x: state.position.x + Math.sin(state.yaw) * state.velocity * dt,
      y: MathUtils.clamp(state.position.y + control.climb * 10 * dt, 0.55, 64),
      z: state.position.z - Math.cos(state.yaw) * state.velocity * dt,
    };
    state.collision = false;
    if (next.y < 1.1) state.collision = true;
    for (const building of buildingColliders) {
      const droneRadius = 1.45;
      const overlapsXZ = Math.abs(next.x - building.x) < building.width / 2 + droneRadius && Math.abs(next.z - building.z) < building.depth / 2 + droneRadius;
      const overlapsY = next.y - 0.8 < building.height && next.y + 0.8 > 0;
      if (overlapsXZ && overlapsY) { state.collision = true; break; }
    }
    if (!state.collision && next.y < 6.2) {
      for (const tile of tiles) {
        const intersectionX = tile.x * TILE + TILE / 2;
        const intersectionZ = tile.z * TILE + TILE / 2;
        for (const [offsetX, offsetZ] of [[-7, -7], [7, -7], [-7, 7], [7, 7]]) {
          if (Math.abs(next.x - intersectionX - offsetX) < 0.75 && Math.abs(next.z - intersectionZ - offsetZ) < 0.75) {
            state.collision = true;
            break;
          }
        }
        if (state.collision) break;
      }
    }
    if (!state.collision && next.y < 3.3) {
      for (const vehicle of state.vehicles) {
        if (Math.abs(next.x - vehicle.x) < 2.5 && Math.abs(next.z - vehicle.z) < 3.8) { state.collision = true; break; }
      }
    }
    if (state.collision) {
      state.position = state.previous;
      state.velocity = 0;
    } else state.position = next;
    const tileKey = keyFor(Math.floor(state.position.x / TILE), Math.floor(state.position.z / TILE));
    const tileDirection = `${Math.round(Math.sin(state.yaw))}:${-Math.round(Math.cos(state.yaw))}`;
    const streamingKey = `${tileKey}:${tileDirection}`;
    if (streamingKey !== previousChunk.current) {
      previousChunk.current = streamingKey;
      const nextTiles = tilesAhead(state.position, state.yaw);
      state.chunks = nextTiles.map((tile) => tile.key);
      setTiles(nextTiles);
    }
  });
  return null;
}

function StereoRenderer({ state }: { state: FlightState }) {
  const { gl, scene, size } = useThree();
  const context = gl.getContext() as typeof gl extends { getContext: () => infer T } ? T & { endFrameEXP?: () => void } : never;
  const presentRef = useRef<(() => void) | null>(null);
  const batchingRef = useRef(false);
  const [leftCamera, rightCamera] = useMemo(() => [
    new PerspectiveCamera(50, 1, 0.1, 260),
    new PerspectiveCamera(50, 1, 0.1, 260),
  ], []);
  const target = useRef(new Vector3());
  useEffect(() => {
    const nativePresent = context.endFrameEXP?.bind(context);
    if (!nativePresent) return undefined;
    presentRef.current = nativePresent;
    context.endFrameEXP = () => { if (!batchingRef.current) nativePresent(); };
    return () => { context.endFrameEXP = nativePresent; };
  }, [context]);
  useFrame((_, delta) => {
    const forwardX = Math.sin(state.viewYaw);
    const forwardZ = Math.cos(state.viewYaw);
    const distance = 15;
    const sideX = Math.cos(state.viewYaw) * 0.045;
    const sideZ = Math.sin(state.viewYaw) * 0.045;
    const aspect = size.width / 2 / Math.max(1, size.height);
    const width = Math.max(2, Math.floor(size.width));
    const height = Math.max(2, Math.floor(size.height));
    const halfWidth = Math.floor(width / 2);

    for (const [camera, eye] of [[leftCamera, -1], [rightCamera, 1]] as const) {
      camera.aspect = aspect;
      camera.updateProjectionMatrix();
      camera.position.set(
        state.position.x - forwardX * Math.cos(state.viewPitch) * distance + sideX * eye,
        state.position.y + 2.4 + Math.sin(state.viewPitch) * distance,
        state.position.z + forwardZ * Math.cos(state.viewPitch) * distance + sideZ * eye,
      );
      target.current.set(state.position.x + sideX * eye, state.position.y + 0.45, state.position.z + sideZ * eye);
      camera.lookAt(target.current);
    }

    gl.setScissorTest(true);
    gl.setViewport(0, 0, width, height);
    gl.setScissor(0, 0, width, height);
    gl.clear(true, true, true);
    batchingRef.current = true;
    gl.setViewport(0, 0, halfWidth, height);
    gl.setScissor(0, 0, halfWidth, height);
    gl.render(scene, leftCamera);
    gl.setViewport(halfWidth, 0, width - halfWidth, height);
    gl.setScissor(halfWidth, 0, width - halfWidth, height);
    gl.render(scene, rightCamera);
    gl.setScissorTest(false);
    batchingRef.current = false;
    presentRef.current?.();
  }, 1);
  return null;
}

function Scene({ state, controls, tiles, setTiles }: {
  state: FlightState; controls: React.MutableRefObject<Controls>; tiles: Tile[]; setTiles: (tiles: Tile[]) => void;
}) {
  const buildingColliders = useMemo(() => tiles.flatMap(makeBuildings), [tiles]);
  return (
    <>
      <FlightLogic state={state} controls={controls} tiles={tiles} buildingColliders={buildingColliders} setTiles={setTiles} />
      <ambientLight intensity={0.95} />
      <hemisphereLight args={['#e1ebdd', '#778979', 0.55]} />
      <directionalLight position={[-26, 48, 20]} intensity={1.6} color="#fff3dd" />
      {tiles.map((tile) => <TileWorld key={tile.key} tile={tile} state={state} />)}
      <Buildings tiles={tiles} />
      <TrafficLights tiles={tiles} state={state} />
      <Drone state={state} />
      <StereoRenderer state={state} />
    </>
  );
}

function SimulationCanvas({ state, controls, tiles, setTiles }: {
  state: FlightState; controls: React.MutableRefObject<Controls>; tiles: Tile[]; setTiles: (tiles: Tile[]) => void;
}) {
  return (
    <Canvas
      camera={{ position: [0, 20, 40], fov: 50, near: 0.1, far: 260 }}
      gl={{ antialias: false, powerPreference: 'high-performance' }}
      onCreated={({ scene }) => { scene.background = new Color('#b8c8b7'); scene.fog = new Fog('#b8c8b7', 100, 240); }}
      style={styles.worldCanvas}
    >
      <Scene state={state} controls={controls} tiles={tiles} setTiles={setTiles} />
    </Canvas>
  );
}

export default function FlightSimulation({ onExit, controls: controllerInput, startHidden = false }: {
  onExit: () => void;
  controls: FlightControls;
  startHidden?: boolean;
}) {
  const { width: windowWidth, height: windowHeight } = useWindowDimensions();
  const [measuredFrame, setMeasuredFrame] = useState({ width: 0, height: 0 });
  const state = useRef<FlightState>({
    position: { x: 0, y: 22, z: 0 }, previous: { x: 0, y: 22, z: 0 }, velocity: 0, yaw: 0, viewYaw: 0, viewPitch: 0.35,
    time: 0, collision: false, chunks: [], vehicles: emptyVehicles.map((vehicle) => ({ ...vehicle })),
  }).current;
  const controls = useRef<Controls>({ ...controllerInput });
  const [tiles, setTiles] = useState(() => tilesAhead(state.position, state.yaw));
  const [telemetry, setTelemetry] = useState({ altitude: state.position.y, speed: 0, collision: false, chunks: tiles.length });
  const [hudVisible, setHudVisible] = useState(!startHidden);
  const [hudPulse, setHudPulse] = useState(0);

  useEffect(() => {
    controls.current = controllerInput;
  }, [controllerInput.yaw, controllerInput.throttle, controllerInput.lookX, controllerInput.lookY, controllerInput.climb]);

  useEffect(() => {
    if (!hudVisible) return undefined;
    const timeout = setTimeout(() => setHudVisible(false), 3500);
    return () => clearTimeout(timeout);
  }, [hudVisible, hudPulse]);

  useEffect(() => {
    const timer = setInterval(() => setTelemetry({ altitude: state.position.y, speed: Math.abs(state.velocity), collision: state.collision, chunks: tiles.length }), 120);
    return () => clearInterval(timer);
  }, [state, tiles.length]);

  return (
    <View
      style={[styles.root, { width: windowWidth, height: windowHeight }]}
      onLayout={(event) => {
        const { width, height } = event.nativeEvent.layout;
        if (width > 0 && height > 0 && (width !== measuredFrame.width || height !== measuredFrame.height)) setMeasuredFrame({ width, height });
      }}
      onTouchStart={() => { setHudVisible(true); setHudPulse((pulse) => pulse + 1); }}
    >
      <StatusBar hidden />
      <View style={[styles.stereoWorld, measuredFrame.width > 0 && { width: measuredFrame.width, height: measuredFrame.height }]}>
        <SimulationCanvas state={state} controls={controls} tiles={tiles} setTiles={setTiles} />
        <View pointerEvents="none" style={styles.stereoDivider} />
      </View>
      {hudVisible && <>
        <View pointerEvents="none" style={styles.hud}>
          <Text style={styles.hudLabel}>SKYCOP / FLIGHT SIM</Text>
          <Text style={styles.hudValue}>{telemetry.altitude.toFixed(0)} M  /  {telemetry.speed.toFixed(1)} M/S  /  {telemetry.chunks} SECTORS</Text>
          <Text style={styles.hudValue}>{Math.round(windowWidth)} × {Math.round(windowHeight)} / GL {Math.round(measuredFrame.width)} × {Math.round(measuredFrame.height)}</Text>
          {telemetry.collision && <Text style={styles.collision}>COLLISION / FLIGHT HELD</Text>}
        </View>
        <Pressable onPress={onExit} accessibilityRole="button" accessibilityLabel="Exit simulation" style={styles.exit}>
          <Text style={styles.exitText}>EXIT</Text>
        </Pressable>
      </>}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, width: '100%', height: '100%', position: 'relative', backgroundColor: '#111714' },
  stereoWorld: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, backgroundColor: '#b8c8b7' },
  worldCanvas: { flex: 1, width: '100%', height: '100%' },
  stereoDivider: { position: 'absolute', top: 0, bottom: 0, left: '50%', width: 2, marginLeft: -1, backgroundColor: '#101412' },
  hud: { position: 'absolute', top: 12, left: 16 },
  hudLabel: { color: '#f5f2e7', fontSize: 9, fontWeight: '700', letterSpacing: 0.5 },
  hudValue: { marginTop: 4, color: '#f5f2e7', fontSize: 9, fontVariant: ['tabular-nums'] },
  collision: { marginTop: 4, color: '#ffd17c', fontSize: 9, fontWeight: '700' },
  exit: { position: 'absolute', top: 10, right: 12, minWidth: 48, minHeight: 34, justifyContent: 'center', alignItems: 'center', borderWidth: 1, borderColor: '#f5f2e780', backgroundColor: '#18211dcc' },
  exitText: { color: '#f5f2e7', fontSize: 10, fontWeight: '700' },
});
